package org.kysecurity.drive

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.snackbar.Snackbar
import com.journeyapps.barcodescanner.ScanContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kysecurity.drive.core.Api
import org.kysecurity.drive.core.ApiError
import org.kysecurity.drive.core.Drive
import org.kysecurity.drive.core.FileItem
import org.kysecurity.drive.core.Folder
import org.kysecurity.drive.core.Item
import org.kysecurity.drive.core.LinkUri
import org.kysecurity.drive.core.Workspace
import java.io.IOException

class MainActivity : SecureActivity() {
    private lateinit var store: SessionStore
    private var session: Session? = null
    private var signedOut = false
    private var linking = false

    /**
     * A folder on screen. [workspace] is null for the Shared root; [direct] lists through the
     * folder-children route, for a folder shared with the user outside their workspaces.
     */
    private data class Place(
        val title: String,
        val workspace: Workspace?,
        val folder: String = "",
        val direct: Boolean = false,
        val role: String = workspace?.role.orEmpty(),
        val trash: Boolean = false,
    ) {
        val writable get() = role == "editor" || role == "manager"
    }

    // One back stack per tab; the Files root is resolved (and the personal workspace created)
    // on first load.
    private val stacks = mutableMapOf<Int, MutableList<Place>>()
    private var tab = R.id.nav_files
    private val stack get() = stacks.getOrPut(tab) { mutableListOf() }
    private var loadJob: Job? = null
    private lateinit var adapter: RowAdapter
    private var saving: FileItem? = null

    // Registered before STARTED. A null result is a cancelled scan or picker.
    private val scanner = registerForActivityResult(ScanContract()) { result -> result.contents?.let(::scanned) }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> upload(uris) }
    private val saver = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val f = saving ?: return@registerForActivityResult
        saving = null
        if (uri != null) save(f, uri)
    }

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = up()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = SessionStore(this)
        onBackPressedDispatcher.addCallback(this, back)
        findViewById<View>(R.id.settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.scan).setOnClickListener { scanner.launch(SecureCaptureActivity.options()) }
        findViewById<View>(R.id.up).setOnClickListener { up() }
        findViewById<View>(R.id.more).setOnClickListener { more() }
        findViewById<View>(R.id.add).setOnClickListener { add() }
        adapter = RowAdapter(::open, ::actions)
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        // The emulator has no camera: screenshot builds take the scanned text as an extra and
        // go through the same parse and confirmation. Compiled out of every other build.
        if (BuildConfig.ALLOW_SCREENSHOTS && savedInstanceState == null) intent.getStringExtra("screenshot_link")?.let(::scanned)
        findViewById<NavigationBarView>(R.id.nav).setOnItemSelectedListener {
            tab = it.itemId
            render()
            load()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        placeRail()
        Work.changed = ::load
        if (linking) return
        val loaded = store.load()
        if (loaded != session) setSession(loaded)
        render()
        load()
    }

    override fun onPause() {
        super.onPause()
        Work.changed = null
    }

    // ---- browsing ----

    private fun load() {
        val s = session ?: return
        if (signedOut) return
        val place = stack.lastOrNull()
        val forTab = tab
        loadJob?.cancel()
        setLoading(true)
        loadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val d = Drive(s.api())
                    val p = place ?: Place(getString(R.string.my_files), if (forTab == R.id.nav_files) d.personal() else null)
                    p to fetch(d, p)
                }
            }
            setLoading(false)
            if (session != s || tab != forTab) return@launch
            result.onSuccess { (p, rows) ->
                if (place == null) stack += if (forTab == R.id.nav_files) p else p.copy(title = getString(R.string.nav_shared))
                adapter.rows = rows
                render()
            }.onFailure { failed(s, it) }
        }
    }

    private fun fetch(d: Drive, p: Place): List<Row> {
        val ws = p.workspace ?: return Row.sorted(
            d.workspaces().filter { it.kind == "shared" }.map { Row.Space(it) } + d.sharedWithMe().map { Row.Direct(it) },
        )
        val listing = when {
            p.trash -> d.list(ws.id, "", trashed = true)
            p.direct -> d.children(p.folder)
            else -> d.list(ws.id, p.folder)
        }
        return Row.sorted(listing.folders.map { Row.Dir(it) } + listing.files.map { Row.Doc(it) })
    }

    private fun open(row: Row) {
        val p = stack.lastOrNull() ?: return
        if (p.trash) return actions(row)
        when (row) {
            is Row.Space -> enter(Place(row.workspace.name, row.workspace))
            is Row.Dir -> enter(Place(row.folder.name, p.workspace, row.folder.id, p.direct, p.role))
            is Row.Doc -> view(row.file)
            is Row.Direct -> {
                val item = row.item
                val ws = Workspace(item.workspace, item.workspaceName, "shared", item.role, 0)
                if (item.kind == "folder") enter(Place(item.name, ws, item.id, direct = true)) else fileThen(item.id, ::view)
            }
        }
    }

    private fun enter(p: Place) {
        stack += p
        adapter.rows = emptyList()
        render()
        load()
    }

    private fun up() {
        if (stack.size < 2) return
        stack.removeAt(stack.lastIndex)
        adapter.rows = emptyList()
        render()
        load()
    }

    /** Opens [f] in another app, streamed from the server; nothing is written to disk. */
    private fun view(f: FileItem) {
        val s = session ?: return
        val uri = OpenProvider.uri(this, s, f)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, OpenProvider.mimeType(f.name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            snack(getString(R.string.no_app))
        }
    }

    /** A directly shared file is listed without its revision and digest; fetch them first. */
    private fun fileThen(id: String, then: (FileItem) -> Unit) {
        val s = session ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { Drive(s.api()).file(id) } }
                .onSuccess(then).onFailure { failed(s, it) }
        }
    }

    private fun actions(row: Row) {
        val p = stack.lastOrNull() ?: return
        val choices = mutableListOf<Pair<Int, () -> Unit>>()
        val item: Item? = when (row) {
            is Row.Dir -> row.folder
            is Row.Doc -> row.file
            else -> null
        }
        if (p.trash) {
            if (p.writable && item != null) choices += R.string.restore to { trash(item, false) }
        } else {
            when (row) {
                is Row.Doc -> {
                    choices += R.string.open to { view(row.file) }
                    choices += R.string.save_to_device to { saveAs(row.file) }
                }
                is Row.Direct -> {
                    choices += R.string.open to { open(row) }
                    if (row.item.kind == "file") choices += R.string.save_to_device to { fileThen(row.item.id, ::saveAs) }
                }
                else -> choices += R.string.open to { open(row) }
            }
            if (p.writable && item != null) {
                choices += R.string.rename to { rename(item) }
                choices += R.string.move_to_trash to { trash(item, true) }
            }
        }
        if (choices.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(row.name)
            .setItems(choices.map { getString(it.first) }.toTypedArray()) { _, i -> choices[i].second() }
            .show()
    }

    private fun more() {
        val p = stack.lastOrNull() ?: return
        val choices = mutableListOf<Pair<Int, () -> Unit>>(R.string.refresh to ::load)
        if (p.workspace != null && !p.trash && !p.direct) {
            choices += R.string.trash to { enter(Place(getString(R.string.trash), p.workspace, trash = true)) }
        }
        MaterialAlertDialogBuilder(this)
            .setItems(choices.map { getString(it.first) }.toTypedArray()) { _, i -> choices[i].second() }
            .show()
    }

    private fun add() {
        MaterialAlertDialogBuilder(this)
            .setItems(arrayOf(getString(R.string.upload), getString(R.string.new_folder))) { _, i ->
                if (i == 0) picker.launch(arrayOf("*/*")) else newFolder()
            }
            .show()
    }

    /** Asks for a name the server will take; [done] runs off the main thread. */
    private fun askName(title: Int, value: String, done: (Drive, String) -> Unit) {
        val s = session ?: return
        val field = privateText(this, value)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(LinearLayout(this).apply { setPadding(48, 16, 48, 0); addView(field, LinearLayout.LayoutParams(-1, -2)) })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, null)
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = field.text.toString().trim()
            if (!Drive.validName(name)) {
                field.error = getString(R.string.bad_name)
                return@setOnClickListener
            }
            dialog.dismiss()
            mutate(s) { done(Drive(s.api()), name) }
        }
    }

    private fun newFolder() {
        val p = stack.lastOrNull() ?: return
        val ws = p.workspace ?: return
        askName(R.string.new_folder, "") { d, name -> d.addFolder(ws.id, p.folder, name) }
    }

    private fun rename(item: Item) {
        askName(R.string.rename, item.name) { d, name ->
            when (item) {
                is FileItem -> d.renameFile(item, name)
                is Folder -> d.renameFolder(item, name)
            }
        }
    }

    private fun trash(item: Item, trashed: Boolean) {
        val s = session ?: return
        mutate(s, if (trashed) getString(R.string.moved_to_trash, item.name) else getString(R.string.restored, item.name), undo = if (trashed) ({ trash(item, false) }) else null) {
            Drive(s.api()).trash(item, trashed)
        }
    }

    /** Runs a quick change, then reloads. */
    private fun mutate(s: Session, done: String? = null, undo: (() -> Unit)? = null, block: () -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching(block) }
                .onSuccess {
                    load()
                    if (done != null) {
                        val bar = Snackbar.make(findViewById(R.id.title), done, Snackbar.LENGTH_LONG)
                        if (undo != null) bar.setAction(R.string.undo) { undo() }
                        bar.show()
                    }
                }
                .onFailure { failed(s, it) }
        }
    }

    private fun upload(uris: List<Uri>) {
        val s = session ?: return
        val p = stack.lastOrNull() ?: return
        val ws = p.workspace ?: return
        for (uri in uris) {
            val (name, size) = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (!it.moveToFirst()) null else it.getString(0).orEmpty() to (if (it.isNull(1)) -1L else it.getLong(1))
            } ?: ("" to -1L)
            if (!Drive.validName(name) || size < 0) {
                snack(getString(R.string.cannot_upload, name.ifEmpty { getString(R.string.this_file) }))
                continue
            }
            snack(getString(R.string.uploading, name))
            val resolver = applicationContext.contentResolver
            Work.run(this, s, getString(R.string.uploaded, name)) {
                val input = resolver.openInputStream(uri) ?: throw IOException("the picker's file is gone")
                Drive(s.api()).upload(ws.id, p.folder, name, size, null, input)
            }
        }
    }

    private fun saveAs(f: FileItem) {
        saving = f
        saver.launch(f.name)
    }

    /** Copies [f] to where the user chose; a failed copy is removed, not left half written. */
    private fun save(f: FileItem, uri: Uri) {
        val s = session ?: return
        snack(getString(R.string.saving, f.name))
        val resolver = applicationContext.contentResolver
        Work.run(this, s, getString(R.string.saved, f.name)) {
            try {
                val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("the chosen place cannot be written")
                out.use { Drive(s.api()).download(f, it) }
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                throw e
            }
        }
    }

    private fun failed(s: Session, e: Throwable) {
        val text = failure(this, s, e)
        when {
            e is ApiError && e.revoked -> setSession(null)
            e is ApiError && e.status == 401 -> signedOut = true
        }
        render()
        snack(text)
    }

    /**
     * Every change of account goes through here, so nothing the previous one listed stays on
     * screen or pending: not its rows, its folders or a save waiting for a destination.
     */
    private fun setSession(next: Session?) {
        session = next
        signedOut = false
        stacks.clear()
        loadJob?.cancel()
        adapter.rows = emptyList()
        saving = null
    }

    // ---- linking ----

    private fun scanned(text: String) {
        val link = LinkUri.parse(text) ?: return snack(getString(R.string.link_not_a_code))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.link_confirm_title)
            .setMessage(getString(R.string.link_confirm_body, link.host))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.link_confirm) { _, _ -> link(link) }
            .show()
    }

    private fun link(link: LinkUri) {
        val existing = session
        linking = true
        render()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val linked = Api(link.origin).redeem(link.code, deviceName(this@MainActivity))
                    val account = Api(link.origin, linked.token).account()
                    if (account.userId != linked.userId) throw IOException("token answered for another account")
                    val next = Session(link.origin, linked.token, linked.userId, linked.user, deviceName(this@MainActivity))
                    // A signed-out phone keeps its cache only for the same account on the same server.
                    if (existing != null && (existing.origin != next.origin || existing.userId != next.userId)) {
                        runCatching { next.api().unlink() }
                        return@runCatching null
                    }
                    store.save(next)
                    next
                }
            }
            linking = false
            result.onSuccess { next ->
                if (next == null) return@onSuccess snack(getString(R.string.link_other_account))
                setSession(next)
                snack(getString(R.string.link_done, next.user))
                load()
            }.onFailure { snack(linkFailure(it, link.host)) }
            render()
        }
    }

    private fun linkFailure(e: Throwable, host: String): String = when {
        e is ApiError && e.status == 400 -> getString(R.string.link_expired)
        e is ApiError -> getString(R.string.link_refused, e.status)
        e is javax.net.ssl.SSLException -> getString(R.string.error_certificate, host)
        else -> getString(R.string.error_unreachable, host)
    }

    // ---- screen ----

    private fun setLoading(on: Boolean) {
        findViewById<View>(R.id.loading).visibility = if (on && adapter.rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun render() {
        val s = session
        val linked = s != null && !signedOut
        val p = stack.lastOrNull()
        fun View.shown(on: Boolean) { visibility = if (on) View.VISIBLE else View.GONE }
        findViewById<View>(R.id.link).shown(!linked)
        findViewById<View>(R.id.nav).shown(linked)
        findViewById<View>(R.id.list).shown(linked && adapter.rows.isNotEmpty())
        findViewById<View>(R.id.scan).shown(!linking)
        findViewById<View>(R.id.linking).shown(linking)
        findViewById<View>(R.id.up).shown(linked && stack.size > 1)
        findViewById<View>(R.id.more).shown(linked && p != null)
        findViewById<View>(R.id.add).shown(linked && p != null && p.workspace != null && p.writable && !p.trash)
        back.isEnabled = linked && stack.size > 1
        findViewById<TextView>(R.id.title).text = when {
            !linked -> getString(R.string.app_name)
            p != null -> p.title
            tab == R.id.nav_files -> getString(R.string.my_files)
            else -> getString(R.string.nav_shared)
        }
        val empty = findViewById<TextView>(R.id.empty)
        empty.shown(linked && p != null && adapter.rows.isEmpty() && findViewById<View>(R.id.loading).visibility != View.VISIBLE)
        empty.setText(
            when {
                p?.trash == true -> R.string.empty_trash
                p?.workspace == null -> R.string.empty_shared
                else -> R.string.empty_folder
            },
        )
        findViewById<TextView>(R.id.link_title).setText(if (signedOut) R.string.link_signed_out_title else R.string.link_title)
        findViewById<TextView>(R.id.link_body).text =
            if (signedOut && s != null) getString(R.string.link_signed_out_body, s.host) else getString(R.string.link_body)
    }

    private fun placeRail() {
        // Only the wide layout has a shell; the side may have changed in Settings.
        val shell = findViewById<LinearLayout>(R.id.shell) ?: return
        val rail = findViewById<View>(R.id.nav)
        val index = if (Settings(this).railSide == RailSide.LEFT) 0 else shell.childCount - 1
        if (shell.indexOfChild(rail) != index) {
            shell.removeView(rail)
            shell.addView(rail, index)
        }
    }

    private fun snack(text: String) {
        Snackbar.make(findViewById(R.id.title), text, Snackbar.LENGTH_LONG).show()
    }
}
