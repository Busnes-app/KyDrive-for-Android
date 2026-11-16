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
import org.kysecurity.drive.core.ApiError
import org.kysecurity.drive.core.Drive
import org.kysecurity.drive.core.FileItem
import org.kysecurity.drive.core.RangeReader
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
        val app = context!!.applicationContext
        val drive = Drive(o.session.api())
        // The generation recorded when the URI was made: a wipe at any point after that,
        // even between the lookup above and here, leaves this reader dead.
        val range = RangeReader(o.file.size, WINDOW, { generation == o.generation }) { off, len ->
            try {
                drive.range(o.file, off, len)
            } catch (e: ApiError) {
                // Revoked while another app reads: wipe now, as any other request would.
                if (e.revoked) SessionStore(app).wipe()
                throw e
            }
        }
        val storage = app.getSystemService(StorageManager::class.java)
        return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, Reader(o.file.size, range), handler)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    private class Reader(private val size: Long, private val range: RangeReader) : ProxyFileDescriptorCallback() {
        override fun onGetSize() = size

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
            range.read(offset, size, data)
        } catch (_: Exception) {
            throw ErrnoException("read", OsConstants.EIO)
        }

        override fun onRelease() = range.close()
    }

    private class Opened(val session: Session, val file: FileItem, val generation: Int)

    companion object {
        private const val WINDOW = 1 shl 20
        private val opened = ConcurrentHashMap<String, Opened>()

        // Bumped by forget(): readers opened under an older generation return nothing more.
        @Volatile private var generation = 0
        private val handler by lazy { Handler(HandlerThread("open-files").apply { start() }.looper) }

        fun uri(context: Context, session: Session, file: FileItem): Uri {
            val token = UUID.randomUUID().toString()
            opened[token] = Opened(session, file, generation)
            return Uri.Builder().scheme("content").authority("${context.packageName}.open").appendPath(token).build()
        }

        /** Unlink and revoke: no granted URI opens again, and open ones stop reading. */
        fun forget() {
            generation++
            opened.clear()
        }

        fun mimeType(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    }
}
