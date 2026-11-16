package org.kysecurity.drive.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** A workspace the user can see. [kind] is "personal" or "shared"; [role] reader, editor or manager. */
data class Workspace(val id: String, val name: String, val kind: String, val role: String, val head: Long) {
    val canWrite: Boolean get() = role == "editor" || role == "manager"
}

/** A folder or file. Names come from the server and are untrusted: shown, never used as paths. */
sealed interface Item {
    val id: String
    val workspace: String
    val parent: String
    val name: String
}

data class Folder(override val id: String, override val workspace: String, override val parent: String, override val name: String) : Item

data class FileItem(
    override val id: String,
    override val workspace: String,
    override val parent: String,
    override val name: String,
    val revision: Long,
    val size: Long,
    val digest: String,
    val modified: String,
) : Item

/** An item someone shared with the user directly, outside their workspaces. */
data class SharedItem(val kind: String, val id: String, val workspace: String, val workspaceName: String, val name: String, val role: String)

/** A transfer whose bytes do not match what the server listed; nothing from it is kept. */
class TransferError(message: String) : IOException(message)

/** One folder's contents. */
data class Listing(val folders: List<Folder>, val files: List<FileItem>)

/** The drive routes a phone uses, on top of [Api]. Blocking; call off the main thread. */
class Drive(private val api: Api) {

    fun workspaces(): List<Workspace> = api.array("GET", "/api/drive/workspaces").objects().map {
        Workspace(it.getString("id"), it.getString("name"), it.getString("kind"), it.optString("role"), it.optLong("head"))
    }

    /** The user's own workspace, created on first use the way the web app does. */
    fun personal(): Workspace {
        workspaces().firstOrNull { it.kind == "personal" }?.let { return it }
        val id = api.call("POST", "/api/drive/personal-workspace").getString("id")
        return workspaces().first { it.id == id }
    }

    /** The live (or trashed) contents of [parent] ("" is the workspace root). */
    fun list(workspace: String, parent: String, trashed: Boolean = false): Listing {
        val q = if (trashed) "?trash=true" else ""
        val folders = api.array("GET", "/api/drive/workspaces/${seg(workspace)}/folders$q").objects().map(::folder)
        val files = api.array("GET", "/api/drive/workspaces/${seg(workspace)}/files$q").objects().map(::file)
        // Trash shows every trashed item flat; a live listing is one folder.
        return if (trashed) Listing(folders, files) else Listing(folders.filter { it.parent == parent }, files.filter { it.parent == parent })
    }

    /** A folder shared directly with the user; its workspace may be unreadable as a whole. */
    fun children(folder: String): Listing {
        val json = api.call("GET", "/api/drive/folders/${seg(folder)}/children")
        return Listing(json.getJSONArray("folders").objects().map(::folder), json.getJSONArray("files").objects().map(::file))
    }

    fun sharedWithMe(): List<SharedItem> = api.array("GET", "/api/drive/shared-with-me").objects().map {
        SharedItem(it.getString("kind"), it.getString("id"), it.getString("workspace"), it.optString("workspace_name"), it.getString("name"), it.optString("role"))
    }

    fun file(id: String): FileItem = file(api.call("GET", "/api/drive/files/${seg(id)}"))

    fun addFolder(workspace: String, parent: String, name: String): Folder =
        folder(api.call("POST", "/api/drive/workspaces/${seg(workspace)}/folders", JSONObject().put("parent", parent).put("name", name)))

    fun renameFile(f: FileItem, name: String): FileItem = file(
        api.call("PUT", "/api/drive/files/${seg(f.id)}/location", JSONObject().put("revision", f.revision).put("workspace", f.workspace).put("parent", f.parent).put("name", name)),
    )

    fun renameFolder(f: Folder, name: String): Folder =
        folder(api.call("PUT", "/api/drive/folders/${seg(f.id)}/location", JSONObject().put("parent", f.parent).put("name", name)))

    fun trash(item: Item, trashed: Boolean) {
        when (item) {
            is FileItem -> api.call("PUT", "/api/drive/files/${seg(item.id)}/trash", JSONObject().put("revision", item.revision).put("trashed", trashed))
            is Folder -> api.call("PUT", "/api/drive/folders/${seg(item.id)}/trash", JSONObject().put("trashed", trashed))
        }
    }

    /**
     * Copies [f] to [out] and fails unless it matches the size and SHA-256 the listing gave, so a
     * changed or damaged download is never kept.
     */
    fun download(f: FileItem, out: java.io.OutputStream) {
        val conn = api.open("GET", "/api/drive/files/${seg(f.id)}/download")
        try {
            if (conn.responseCode != 200) throw Api.error(conn.responseCode, conn.errorStream)
            val sha = MessageDigest.getInstance("SHA-256")
            var n = 0L
            conn.inputStream.use { input ->
                val buf = ByteArray(64 shl 10)
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    n += r
                    if (n > f.size) throw TransferError("the download is larger than the file")
                    sha.update(buf, 0, r)
                    out.write(buf, 0, r)
                }
            }
            if (n != f.size || hex(sha.digest()) != f.digest) throw TransferError("the download does not match the file's digest")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Reads [length] bytes of [f] from [offset], only while the server still holds that exact
     * version: If-Range makes a changed file a full 200, which is refused.
     */
    fun range(f: FileItem, offset: Long, length: Int): ByteArray {
        val conn = api.open("GET", "/api/drive/files/${seg(f.id)}/download")
        conn.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
        conn.setRequestProperty("If-Range", "\"${f.digest}\"")
        try {
            when (conn.responseCode) {
                206 -> {}
                200 -> throw TransferError("the file changed on the server")
                else -> throw Api.error(conn.responseCode, conn.errorStream)
            }
            return conn.inputStream.use { it.readExactly(length) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Uploads a new file through a resumable session in [CHUNK] pieces. [source] yields the
     * content once, front to back; a failed chunk is resent from the server's offset, which is
     * never behind the chunk in hand.
     */
    fun upload(workspace: String, parent: String, name: String, size: Long, mtime: String?, source: InputStream): FileItem {
        val start = JSONObject().put("parent", parent).put("name", name).put("file", "").put("revision", 0).put("size", size)
        mtime?.let { start.put("mtime", it) }
        val sid = api.call("POST", "/api/drive/workspaces/${seg(workspace)}/upload-sessions", start).getString("id")
        val path = "/api/drive/upload-sessions/${seg(sid)}"
        val chunk = ByteArray(CHUNK)
        var base = 0L
        source.use { input ->
            while (base < size) {
                val len = input.readUpTo(chunk, minOf(CHUNK.toLong(), size - base).toInt())
                if (len == 0) throw TransferError("the file is shorter than its size")
                var sent = 0
                var fails = 0
                while (sent < len) {
                    try {
                        api.put(path, "bytes ${base + sent}-${base + len - 1}/$size", chunk, sent, len - sent)
                        sent = len
                    } catch (e: IOException) {
                        if (e is ApiError && e.status != 409 && e.status < 500) throw e
                        if (++fails == 5) throw e
                        val received = api.call("GET", path).getLong("received")
                        if (received < base || received > base + len) throw e
                        sent = (received - base).toInt()
                    }
                }
                base += len
            }
        }
        return file(api.call("POST", "$path/complete"))
    }

    companion object {
        const val CHUNK = 8 shl 20

        /** The server's ValidName: 1–255 UTF-8 bytes, untrimmed, not . or .., no slashes or control or format characters. */
        fun validName(name: String): Boolean {
            if (name.isEmpty() || name.toByteArray().size > 255 || name.trim() != name || name == "." || name == "..") return false
            return name.codePoints().toArray().none {
                it == '/'.code || it == '\\'.code || Character.getType(it).let { t -> t == Character.CONTROL.toInt() || t == Character.FORMAT.toInt() }
            }
        }

        private fun seg(id: String): String = Api.enc(id).replace("+", "%20")

        private fun folder(o: JSONObject) = Folder(o.getString("id"), o.getString("workspace"), o.optString("parent"), o.getString("name"))

        private fun file(o: JSONObject) = FileItem(
            o.getString("id"), o.getString("workspace"), o.optString("parent"), o.getString("name"),
            o.getLong("revision"), o.getLong("size"), o.optString("digest"), o.optString("modified"),
        )

        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

        private fun InputStream.readUpTo(buf: ByteArray, max: Int): Int {
            var n = 0
            while (n < max) {
                val r = read(buf, n, max - n)
                if (r < 0) break
                n += r
            }
            return n
        }

        private fun InputStream.readExactly(len: Int): ByteArray {
            val buf = ByteArray(len)
            val n = readUpTo(buf, len)
            return if (n == len) buf else buf.copyOf(n)
        }
    }
}
