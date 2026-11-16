package org.kysecurity.drive

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.MimeTypeMap
import org.kysecurity.drive.core.Drive
import org.kysecurity.drive.core.FileItem
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Hands a file to the app the user opens it in, without a copy on disk: reads go to the server
 * as ranges pinned to the listed version (If-Range on its digest). Not exported; each URI is
 * granted to one app by the intent that opens it, and is an unguessable token that lives as
 * long as the process.
 */
class OpenProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri): String? = opened[uri.lastPathSegment]?.let { mimeType(it.file.name) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val o = opened[uri.lastPathSegment] ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { if (it == OpenableColumns.SIZE) o.file.size else if (it == OpenableColumns.DISPLAY_NAME) o.file.name else null })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val o = opened[uri.lastPathSegment] ?: throw FileNotFoundException()
        if (mode != "r") throw SecurityException("read only")
        val storage = context!!.getSystemService(StorageManager::class.java)
        return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, Reader(Drive(o.session.api()), o.file), handler)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    /** Serves reads from a read-ahead window, fetching the next window from the server on a miss. */
    private class Reader(private val drive: Drive, private val file: FileItem) : ProxyFileDescriptorCallback() {
        private var start = 0L
        private var window = ByteArray(0)

        override fun onGetSize() = file.size

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            if (offset >= file.size) return 0
            val want = minOf(size.toLong(), file.size - offset).toInt()
            if (offset < start || offset + want > start + window.size) {
                val len = minOf(maxOf(want, WINDOW).toLong(), file.size - offset).toInt()
                window = try {
                    drive.range(file, offset, len)
                } catch (_: Exception) {
                    throw ErrnoException("read", OsConstants.EIO)
                }
                start = offset
                if (window.size < want) throw ErrnoException("read", OsConstants.EIO)
            }
            System.arraycopy(window, (offset - start).toInt(), data, 0, want)
            return want
        }

        override fun onRelease() {
            window = ByteArray(0)
        }
    }

    private class Opened(val session: Session, val file: FileItem)

    companion object {
        private const val WINDOW = 1 shl 20
        private val opened = ConcurrentHashMap<String, Opened>()
        private val handler by lazy { Handler(HandlerThread("open-files").apply { start() }.looper) }

        fun uri(context: Context, session: Session, file: FileItem): Uri {
            val token = UUID.randomUUID().toString()
            opened[token] = Opened(session, file)
            return Uri.Builder().scheme("content").authority("${context.packageName}.open").appendPath(token).build()
        }

        /** Unlink and revoke: no granted URI keeps reading. */
        fun forget() = opened.clear()

        fun mimeType(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    }
}
