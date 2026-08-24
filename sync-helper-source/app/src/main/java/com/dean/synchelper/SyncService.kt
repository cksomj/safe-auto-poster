package com.dean.synchelper

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import android.provider.DocumentsContract
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SyncService : Service() {
    private val executor = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private lateinit var nsd: NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var serverSocket: ServerSocket? = null
    private val prefs by lazy { getSharedPreferences("sync", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sync", "Sync Helper", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("received", "백업 수신", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.compareAndSet(false, true)) {
            prefs.edit().putBoolean("running", true).apply()
            startForeground(10, serviceNotification("Sync Helper 실행 중"))
            if (prefs.getString("role", "receiver") == "sender") startSender() else startReceiver()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        prefs.edit().putBoolean("running", false).apply()
        try { serverSocket?.close() } catch (_: Exception) {}
        registrationListener?.let { try { nsd.unregisterService(it) } catch (_: Exception) {} }
        discoveryListener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startReceiver() {
        executor.execute {
            try {
                val server = ServerSocket(0)
                serverSocket = server
                registerService(server.localPort)
                while (running.get()) executor.execute { receiveFile(server.accept()) }
            } catch (_: Exception) {}
        }
    }

    private fun registerService(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "SyncHelper-${android.os.Build.MODEL}"
            serviceType = "_synchelper._tcp."
            setPort(port)
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun receiveFile(socket: Socket) {
        socket.use { s ->
            val input = DataInputStream(BufferedInputStream(s.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            if (input.readUTF() != prefs.getString("code", "")) { output.writeBoolean(false); output.flush(); return }
            output.writeBoolean(true); output.flush()
            val name = sanitize(input.readUTF())
            val size = input.readLong()
            val expectedHash = input.readUTF()
            if (size <= 0 || size > 2L * 1024 * 1024 * 1024) return
            val dir = File(filesDir, "backups").apply { mkdirs() }
            val outFile = File(dir, name)
            val md = MessageDigest.getInstance("SHA-256")
            FileOutputStream(outFile).use { fos ->
                val buffer = ByteArray(64 * 1024)
                var remaining = size
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) throw EOFException()
                    fos.write(buffer, 0, read); md.update(buffer, 0, read); remaining -= read
                }
            }
            val actualHash = md.digest().joinToString("") { "%02x".format(it) }
            if (!actualHash.equals(expectedHash, true)) { outFile.delete(); return }
            prefs.edit().putString("latestBackup", outFile.absolutePath).apply()
            showReceivedNotification(outFile)
        }
    }

    private fun startSender() {
        discoverReceiver()
        executor.execute {
            var lastId = prefs.getString("lastSentId", "") ?: ""
            while (running.get()) {
                try {
                    val newest = findNewestBackup()
                    val peer = PeerRegistry.get()
                    if (newest != null && newest.id != lastId && peer != null && sendFile(peer, newest)) {
                        lastId = newest.id
                        prefs.edit().putString("lastSentId", lastId).apply()
                    }
                    Thread.sleep(10_000)
                } catch (_: InterruptedException) { break } catch (_: Exception) { try { Thread.sleep(10_000) } catch (_: Exception) {} }
            }
        }
    }

    private fun discoverReceiver() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) { PeerRegistry.clear() }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != "_synchelper._tcp.") return
                @Suppress("DEPRECATION")
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host ?: return
                        PeerRegistry.set(Peer(host, resolved.port))
                    }
                })
            }
        }
        nsd.discoverServices("_synchelper._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun findNewestBackup(): BackupDoc? {
        val tree = prefs.getString("folderUri", null) ?: return null
        val treeUri = Uri.parse(tree)
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
        var newest: BackupDoc? = null
        contentResolver.query(children, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE
        ), null, null, null)?.use { c ->
            val idIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val modIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            while (c.moveToNext()) {
                val name = c.getString(nameIx) ?: continue
                if (!name.endsWith(".jwlibrary", true)) continue
                val id = c.getString(idIx)
                val modified = c.getLong(modIx)
                val size = c.getLong(sizeIx)
                val item = BackupDoc("$id:$modified:$size", name, DocumentsContract.buildDocumentUriUsingTree(treeUri, id), modified)
                if (newest == null || item.modified > newest!!.modified) newest = item
            }
        }
        return newest
    }

    private fun sendFile(peer: Peer, doc: BackupDoc): Boolean = try {
        Socket(peer.host, peer.port).use { socket ->
            socket.soTimeout = 15000
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            output.writeUTF(prefs.getString("code", "") ?: ""); output.flush()
            if (!input.readBoolean()) return false
            val md = MessageDigest.getInstance("SHA-256")
            var size = 0L
            contentResolver.openInputStream(doc.uri)?.use { src ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = src.read(buffer); if (n <= 0) break; md.update(buffer, 0, n); size += n }
            } ?: return false
            output.writeUTF(doc.name); output.writeLong(size); output.writeUTF(md.digest().joinToString("") { "%02x".format(it) })
            contentResolver.openInputStream(doc.uri)?.use { src ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = src.read(buffer); if (n <= 0) break; output.write(buffer, 0, n) }
            } ?: return false
            output.flush(); true
        }
    } catch (_: Exception) { false }

    private fun serviceNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "sync").setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Sync Helper").setContentText(text).setContentIntent(pi).setOngoing(true).build()
    }

    private fun showReceivedNotification(file: File) {
        val pi = PendingIntent.getActivity(this, 2, Intent(this, RestoreActivity::class.java).putExtra("path", file.absolutePath), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "received").setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("JW Library 백업 수신 완료").setContentText(file.name).setContentIntent(pi).setAutoCancel(true).addAction(Notification.Action.Builder(null, "JW Library에서 복구", pi).build()).build()
        getSystemService(NotificationManager::class.java).notify(20, n)
    }

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9._가-힣-]"), "_")
}

data class Peer(val host: InetAddress, val port: Int)
data class BackupDoc(val id: String, val name: String, val uri: Uri, val modified: Long)
object PeerRegistry {
    @Volatile private var peer: Peer? = null
    fun set(p: Peer) { peer = p }
    fun get(): Peer? = peer
    fun clear() { peer = null }
}
