package com.dean.synchelper

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

class BackupProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun resolve(uri: Uri): File {
        val name = Uri.decode(uri.lastPathSegment ?: "")
        require(!name.contains("/") && !name.contains("\\"))
        val base = File(requireNotNull(context).filesDir, "backups").canonicalFile
        val file = File(base, name).canonicalFile
        require(file.path.startsWith(base.path))
        return file
    }

    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = resolve(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        val row = cursor.newRow()
        cols.forEach { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> row.add(file.name)
                OpenableColumns.SIZE -> row.add(file.length())
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
