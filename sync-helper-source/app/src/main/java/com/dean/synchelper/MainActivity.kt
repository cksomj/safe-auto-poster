package com.dean.synchelper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("sync", MODE_PRIVATE) }
    private lateinit var sender: RadioButton
    private lateinit var receiver: RadioButton
    private lateinit var code: EditText
    private lateinit var folderText: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(TextView(this).apply { text = "Sync Helper"; textSize = 28f }, lp())
        root.addView(TextView(this).apply { text = "S25 / S26 · 같은 Wi-Fi 자동 백업 전달"; textSize = 15f }, lp())
        val rg = RadioGroup(this)
        sender = RadioButton(this).apply { text = "태블릿 · 보내기" }
        receiver = RadioButton(this).apply { text = "스마트폰 · 받기" }
        rg.addView(sender); rg.addView(receiver); root.addView(rg, lp())
        code = EditText(this).apply { hint = "두 기기에 같은 6자리 코드"; inputType = 2; setText(prefs.getString("code", "")) }
        root.addView(code, lp())
        val folderBtn = Button(this).apply { text = "태블릿 백업 폴더 지정" }
        root.addView(folderBtn, lp())
        folderText = TextView(this).apply { text = prefs.getString("folderName", "폴더 미지정") }
        root.addView(folderText, lp())
        val start = Button(this).apply { text = "동기화 시작" }
        val stop = Button(this).apply { text = "동기화 중지" }
        root.addView(start, lp()); root.addView(stop, lp())
        status = TextView(this).apply { textSize = 16f }
        root.addView(status, lp())
        setContentView(ScrollView(this).apply { addView(root) })

        if (prefs.getString("role", "receiver") == "sender") sender.isChecked = true else receiver.isChecked = true
        folderBtn.setOnClickListener { chooseFolder() }
        start.setOnClickListener { startSync() }
        stop.setOnClickListener { stopService(Intent(this, SyncService::class.java)); status.text = "동기화 중지됨" }
        status.text = if (prefs.getBoolean("running", false)) "동기화 서비스 실행 중" else "대기 중"
    }

    private fun chooseFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, 1001)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString("folderUri", uri.toString()).putString("folderName", uri.toString()).apply()
            folderText.text = uri.toString()
        }
    }

    private fun startSync() {
        val c = code.text.toString().trim()
        if (c.length != 6) { Toast.makeText(this, "6자리 연동 코드를 입력해 주세요.", Toast.LENGTH_SHORT).show(); return }
        val role = if (sender.isChecked) "sender" else "receiver"
        if (role == "sender" && prefs.getString("folderUri", null) == null) { Toast.makeText(this, "백업 폴더를 먼저 지정해 주세요.", Toast.LENGTH_SHORT).show(); return }
        prefs.edit().putString("role", role).putString("code", c).apply()
        if (!requestNeededPermissions()) launch(role)
    }

    private fun requestNeededPermissions(): Boolean {
        val list = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) list += Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) list += Manifest.permission.NEARBY_WIFI_DEVICES
        if (list.isNotEmpty()) { requestPermissions(list.toTypedArray(), 2001); return true }
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2001) launch(prefs.getString("role", "receiver") ?: "receiver")
    }

    private fun launch(role: String) {
        startForegroundService(Intent(this, SyncService::class.java))
        status.text = if (role == "sender") "태블릿 송신 대기 중" else "스마트폰 수신 대기 중"
    }
}
