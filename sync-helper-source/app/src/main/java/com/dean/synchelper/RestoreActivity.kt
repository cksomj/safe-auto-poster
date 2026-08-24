package com.dean.synchelper

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.io.File

class RestoreActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path") ?: getSharedPreferences("sync", MODE_PRIVATE).getString("latestBackup", null)
        if (path == null || !File(path).exists()) {
            Toast.makeText(this, "수신된 백업 파일을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        AlertDialog.Builder(this)
            .setTitle("JW Library 백업 복구")
            .setMessage("${File(path).name}\n\nJW Library를 열어 이 백업을 복구하시겠습니까?")
            .setPositiveButton("복구 실행") { _, _ -> openInJw(path) }
            .setNegativeButton("나중에") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun openInJw(path: String) {
        val file = File(path)
        val uri = Uri.parse("content://com.dean.synchelper.backups/${Uri.encode(file.name)}")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/octet-stream")
            setPackage("org.jw.jwlibrary.mobile")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            grantUriPermission("org.jw.jwlibrary.mobile", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "JW Library를 열 수 없습니다. 설치 여부를 확인해 주세요.", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
