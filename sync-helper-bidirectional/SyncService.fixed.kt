package com.dean.synchelper

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.DocumentsContract
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SyncService : Service() {
    private val executor = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private lateinit var nsd: NsdManager
    private lateinit var cm: ConnectivityManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var serverSocket: ServerSocket? = null
    private val peers = ConcurrentHashMap<String, Peer>()
    private val prefs by lazy { getSharedPreferences("sync", MODE_PRIVATE) }
    private val deviceId: String by lazy {
        prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }
    }

    override fun onCreate() {
        super.onCreate()
        nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!prefs.getBoolean("enabled", false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running.compareAndSet(false, true)) {
            prefs.edit().putBoolean("running", true).apply()
            startForeground(10, serviceNotification("양방향 자동 동기화 실행 중"))
            startReceiver()
            startDiscovery()
            watchNetwork()
            startSendLoop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        prefs.edit().putBoolean("running", false).apply()
        try { serverSocket?.close() } catch (_: Exception) { }
        stopNsd()
        networkCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) { } }
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
                while (running.get()) {
                    val socket = server.accept()
                    executor.execute { receiveFile(socket) }
                }
            } catch (_: Exception) { }
        }
    }

    private fun pairHash(): String = sha256Text(prefs.getString("code", "") ?: "").take(12)

    private fun registerService(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "SyncHelper-${Build.MODEL}-${deviceId.take(6)}"
            serviceType = "_synchelper._tcp."
            setPort(port)
            try {
                setAttribute("deviceId", deviceId)
                setAttribute("pair", pairHash())
            } catch (_: Exception) { }
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) { }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) { }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { }
        }
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener) } catch (_: Exception) { }
    }

    private fun startDiscovery() {
        if (discoveryListener != null) return
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { discoveryListener = null }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { }
            override fun onDiscoveryStopped(serviceType: String) { discoveryListener = null }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                peers.entries.removeIf { it.value.serviceName == serviceInfo.serviceName }
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName.contains(deviceId.take(6))) return
                resolve(serviceInfo)
            }
        }
        try { nsd.discoverServices("_synchelper._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
        catch (_: Exception) { discoveryListener = null }
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { }
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host ?: return
                    val remoteId = try { resolved.attributes["deviceId"]?.toString(Charsets.UTF_8) } catch (_: Exception) { null }
                    val remotePair = try { resolved.attributes["pair"]?.toString(Charsets.UTF_8) } catch (_: Exception) { null }
                    if (remoteId == deviceId) return
                    if (remotePair != null && remotePair != pairHash()) return
                    val key = remoteId ?: resolved.serviceName
                    peers[key] = Peer(key, resolved.serviceName, host, resolved.port)
                }
            })
        } catch (_: Exception) { }
    }

    private fun stopNsd() {
        registrationListener?.let { try { nsd.unregisterService(it) } catch (_: Exception) { } }
        registrationListener = null
        discoveryListener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) { } }
        discoveryListener = null
        peers.clear()
    }

    private fun restartDiscoverySoon() {
        executor.execute {
            stopDiscoveryOnly()
            try { Thread.sleep(1200) } catch (_: InterruptedException) { return@execute }
            if (running.get()) startDiscovery()
        }
    }

    private fun stopDiscoveryOnly() {
        discoveryListener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) { } }
        discoveryListener = null
        peers.clear()
    }

    private fun watchNetwork() {
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { restartDiscoverySoon() }
            override fun onLost(network: Network) { peers.clear() }
        }
        networkCallback = cb
        try { cm.registerNetworkCallback(request, cb) } catch (_: Exception) { }
    }

    private fun receiveFile(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 60_000
                val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                val code = input.readUTF()
                val senderId = input.readUTF()
                if (code != prefs.getString("code", "") || senderId == deviceId) {
                    output.writeBoolean(false); output.flush(); return
                }
                output.writeBoolean(true); output.flush()
                val name = sanitize(input.readUTF())
                val size = input.readLong()
                val expectedHash = input.readUTF().lowercase()
                if (size <= 0 || size > 2L * 1024 * 1024 * 1024) {
                    output.writeBoolean(false); output.flush(); return
                }
                if (wasReceived(expectedHash)) {
                    drain(input, size); output.writeBoolean(true); output.flush(); return
                }
                val dir = File(filesDir, "backups").apply { mkdirs() }
                val outFile = File(dir, "${System.currentTimeMillis()}_$name")
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
                val actualHash = md.digest().hex()
                if (!actualHash.equals(expectedHash, true)) {
                    outFile.delete(); output.writeBoolean(false); output.flush(); return
                }
                rememberReceived(expectedHash)
                prefs.edit().putString("latestBackup", outFile.absolutePath).apply()
                output.writeBoolean(true); output.flush()
                showReceivedNotification(outFile)
            } catch (_: Exception) { }
        }
    }

    private fun drain(input: InputStream, size: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n <= 0) break
            remaining -= n
        }
    }

    private fun startSendLoop() {
        executor.execute {
            while (running.get()) {
                try {
                    val newest = findNewestBackup()
                    if (newest != null) {
                        val fingerprint = newest.fingerprint
                        val alreadySent = prefs.getString("lastSentFingerprint", "") == fingerprint
                        if (!alreadySent) {
                            prefs.edit().putString("pendingName", newest.name).apply()
                            for (peer in peers.values.toList()) {
                                if (sendFile(peer, newest)) {
                                    prefs.edit().putString("lastSentFingerprint", fingerprint).remove("pendingName").apply()
                                    updateServiceNotification("최신 백업 전달 완료")
                                    break
                                }
                            }
                        } else if (prefs.contains("pendingName")) {
                            prefs.edit().remove("pendingName").apply()
                        }
                    }
                    Thread.sleep(5_000)
                } catch (_: InterruptedException) { break }
                catch (_: Exception) { try { Thread.sleep(5_000) } catch (_: Exception) { } }
            }
        }
    }

    private fun findNewestBackup(): BackupDoc? {
        val tree = prefs.getString("folderUri", null) ?: return null
        val treeUri = Uri.parse(tree)
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
        var newest: BackupDoc? = null
        contentResolver.query(children, arrayOf( DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE ), null, null, null)?.use { c ->
            val idIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val modIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            while (c.moveToNext()) {
                val name = c.getString(nameIx) ?: continue
                if (!name.endsWith(".jwlibrary", true)) continue
                val id = c.getString(idIx); val modified = c.getLong(modIx); val size = c.getLong(sizeIx)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                val item = BackupDoc("$id:$modified:$size", name, docUri, modified, size)
                if (newest == null || item.modified > newest!!.modified) newest = item
            }
        }
        return newest
    }

    private fun sendFile(peer: Peer, doc: BackupDoc): Boolean {
        return try {
            val (size, hash) = measureAndHash(doc.uri) ?: return false
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(peer.host, peer.port), 8_000)
                socket.soTimeout = 60_000
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                output.writeUTF(prefs.getString("code", "") ?: "")
                output.writeUTF(deviceId)
                output.flush()
                if (!input.readBoolean()) return false
                output.writeUTF(doc.name); output.writeLong(size); output.writeUTF(hash)
                contentResolver.openInputStream(doc.uri)?.use { source ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val n = source.read(buffer); if (n <= 0) break; output.write(buffer, 0, n) }
                } ?: return false
                output.flush()
                input.readBoolean()
            }
        } catch (_: Exception) { false }
    }

    private fun measureAndHash(uri: Uri): Pair<Long, String>? {
        val md = MessageDigest.getInstance("SHA-256"); var size = 0L
        contentResolver.openInputStream(uri)?.use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = source.read(buffer); if (n <= 0) break; md.update(buffer, 0, n); size += n }
        } ?: return null
        return size to md.digest().hex()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sync", "Sync Helper", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("received", "백업 수신", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun serviceNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "sync").setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Sync Helper").setContentText(text).setContentIntent(pi).setOngoing(true).build()
    }
    private fun updateServiceNotification(text: String) { getSystemService(NotificationManager::class.java).notify(10, serviceNotification(text)) }

    private fun showReceivedNotification(file: File) {
        val intent = Intent(this, RestoreActivity::class.java).putExtra("path", file.absolutePath)
        val pi = PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "received").setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("새 JW Library 백업이 도착했습니다").setContentText("${file.name.substringAfter('_')} · 복구하시겠습니까?").setContentIntent(pi).setAutoCancel(true).addAction(Notification.Action.Builder(null, "JW Library에서 복구", pi).build()).build()
        getSystemService(NotificationManager::class.java).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
    }

    private fun wasReceived(hash: String): Boolean = receivedSet().contains(hash)
    private fun rememberReceived(hash: String) {
        val list = receivedSet().toMutableList(); list.remove(hash); list.add(hash)
        while (list.size > 20) list.removeAt(0)
        prefs.edit().putString("receivedHashes", list.joinToString(",")).apply()
    }
    private fun receivedSet(): Set<String> = prefs.getString("receivedHashes", "")!!.split(',').filter { it.isNotBlank() }.toSet()
    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9._가-힣-]"), "_")
    private fun sha256Text(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).hex()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}

data class Peer(val id: String, val serviceName: String, val host: InetAddress, val port: Int)
data class BackupDoc(val fingerprint: String, val name: String, val uri: Uri, val modified: Long, val size: Long)
