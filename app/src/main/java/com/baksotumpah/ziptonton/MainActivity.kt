package com.baksotumpah.ziptonton

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var store: Store
    private lateinit var onboarding: View
    private lateinit var refresh: SwipeRefreshLayout
    private val adapter = LibraryAdapter()
    private val io = Executors.newSingleThreadExecutor()
    private var films = listOf<Film>()
    private var scanning = false

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) setFolder(uri)
    }

    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) io.execute {
            val ok = try {
                contentResolver.openOutputStream(uri)?.use { it.write(store.exportBackup().toByteArray()) } != null
            } catch (_: Exception) { false }
            runOnUiThread { Toast.makeText(this, if (ok) R.string.backup_done else R.string.backup_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore(uri)
    }

    private fun confirmRestore(uri: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_title)
            .setMessage(R.string.restore_msg)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.restore_ok) { _, _ ->
                io.execute {
                    val n = try {
                        val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        if (text == null || text.length > 5_000_000) -1 else store.importBackup(text)
                    } catch (_: Exception) { -1 }
                    runOnUiThread {
                        val msg = if (n < 0) getString(R.string.restore_invalid) else getString(R.string.restore_done, n)
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        if (n >= 0) adapter.rebuild()
                    }
                }
            }
            .show()
    }

    private fun showSettings(anchor: View) {
        val me = this
        PopupMenu(me, anchor).apply {
            menu.add(0, 0, 0, me.getString(R.string.menu_version, Updater.currentCode(me))).isEnabled = false
            menu.add(0, 1, 1, R.string.menu_check_update)
            menu.add(0, 2, 2, R.string.menu_backup)
            menu.add(0, 3, 3, R.string.menu_restore)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> Updater.checkNow(me)
                    2 -> createBackup.launch("ZipTonton-backup.json")
                    3 -> openBackup.launch(arrayOf("*/*"))
                }
                true
            }
            show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)

        onboarding = findViewById(R.id.onboarding)
        findViewById<View>(R.id.pickFolder).setOnClickListener { pickFolder.launch(null) }

        refresh = findViewById(R.id.refresh)
        refresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.lamp))
        refresh.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this, R.color.surface_2))
        refresh.setOnRefreshListener { scan() }

        val grid = GridLayoutManager(this, 2)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (adapter.getItemViewType(position) == TYPE_FILM) 1 else 2
        }
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = grid
            adapter = this@MainActivity.adapter
        }
        Updater.checkOnLaunch(this)
    }

    override fun onResume() {
        super.onResume()
        Updater.resumePending(this)
        val folder = store.folderUri
        onboarding.visibility = if (folder == null) View.VISIBLE else View.GONE
        refresh.visibility = if (folder == null) View.GONE else View.VISIBLE
        if (folder != null) scan()
    }

    private fun setFolder(uri: Uri) {
        store.folderUri?.let { old ->
            try { contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        }
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        store.folderUri = uri.toString()
        films = emptyList()
        onResume()
    }

    private fun scan() {
        val folder = store.folderUri ?: return
        if (scanning) return
        scanning = true
        if (films.isEmpty()) refresh.isRefreshing = true
        io.execute {
            val result = try { Library.scan(this, Uri.parse(folder)) } catch (_: Exception) { null }
            runOnUiThread {
                scanning = false
                refresh.isRefreshing = false
                if (result == null) {
                    // Izin folder hilang (folder dihapus/dipindah): minta pilih ulang
                    store.folderUri = null
                    onResume()
                    return@runOnUiThread
                }
                films = result
                adapter.rebuild()
            }
        }
    }

    private fun openFilm(f: Film) {
        startActivity(Intent(this, FilmActivity::class.java)
            .putExtra(FilmActivity.EXTRA_URI, f.uri.toString())
            .putExtra(FilmActivity.EXTRA_KEY, f.key))
    }

    private fun play(f: Film, entry: String) {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_URI, f.uri.toString())
            .putExtra(PlayerActivity.EXTRA_KEY, f.key)
            .putExtra(PlayerActivity.EXTRA_ENTRY, entry))
    }

    // ---------------- Daftar ----------------

    private sealed class Row {
        object Header : Row()
        data class Hero(val film: Film, val entry: String) : Row()
        object Section : Row()
        object Empty : Row()
        data class Item(val film: Film) : Row()
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_HERO = 1
        const val TYPE_SECTION = 2
        const val TYPE_EMPTY = 3
        const val TYPE_FILM = 4
    }

    inner class LibraryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows = listOf<Row>()

        fun rebuild() {
            val r = ArrayList<Row>()
            r += Row.Header
            store.lastWatched()?.let { (key, entry) ->
                films.firstOrNull { it.key == key && entry in it.entryNames }?.let { r += Row.Hero(it, entry) }
            }
            r += Row.Section
            if (films.isEmpty()) r += Row.Empty else films.forEach { r += Row.Item(it) }
            rows = r
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when (rows[position]) {
            Row.Header -> TYPE_HEADER
            is Row.Hero -> TYPE_HERO
            Row.Section -> TYPE_SECTION
            Row.Empty -> TYPE_EMPTY
            is Row.Item -> TYPE_FILM
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            val layout = when (viewType) {
                TYPE_HEADER -> R.layout.row_header
                TYPE_HERO -> R.layout.row_hero
                TYPE_SECTION -> R.layout.row_section
                TYPE_EMPTY -> R.layout.row_empty
                else -> R.layout.item_film
            }
            return object : RecyclerView.ViewHolder(inf.inflate(layout, parent, false)) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val v = h.itemView
            when (val row = rows[position]) {
                Row.Header -> {
                    val folder = store.folderUri?.let { Library.folderName(Uri.parse(it)) } ?: ""
                    v.findViewById<TextView>(R.id.folderName).text = folder
                    v.findViewById<View>(R.id.changeFolder).setOnClickListener { pickFolder.launch(null) }
                    v.findViewById<View>(R.id.settings).setOnClickListener { showSettings(it) }
                }
                is Row.Hero -> bindHero(v, row.film, row.entry)
                Row.Section -> {
                    v.findViewById<TextView>(R.id.count).text = getString(R.string.film_count, films.size)
                }
                Row.Empty -> {
                    val folder = store.folderUri?.let { Library.folderName(Uri.parse(it)) } ?: ""
                    v.findViewById<TextView>(R.id.emptyText).text = getString(R.string.empty_folder, folder)
                }
                is Row.Item -> bindFilm(v, row.film)
            }
        }

        private fun bindHero(v: View, f: Film, entry: String) {
            val idx = f.entryNames.indexOf(entry)
            val p = store.progress(f.key, entry)
            v.findViewById<TextView>(R.id.heroTitle).text = f.title
            val partText = if (f.videos.size > 1) getString(R.string.part_of, idx + 1, f.videos.size) else store.displayName(f.key, entry)
            v.findViewById<TextView>(R.id.heroSub).text = if (p != null && !p.finished && p.durationMs > 0)
                getString(R.string.hero_sub, partText, Fmt.remaining(p.positionMs, p.durationMs).lowercase())
            else partText
            v.findViewById<SegmentBar>(R.id.heroBar).setParts(f.entryNames.map { store.progress(f.key, it)?.fraction ?: 0f })
            Thumbs.loadFilm(v.findViewById(R.id.heroImage), f, entry)
            v.findViewById<TextView>(R.id.heroInitial).text = f.title.take(1).uppercase()
            v.setOnClickListener { play(f, entry) }
            v.findViewById<View>(R.id.heroPlay).setOnClickListener { play(f, entry) }
        }

        private fun bindFilm(v: View, f: Film) {
            val names = f.entryNames
            val prog = names.map { store.progress(f.key, it) }
            v.findViewById<TextView>(R.id.title).text = f.title
            v.findViewById<TextView>(R.id.initial).text = f.title.take(1).uppercase()
            val done = prog.count { it?.finished == true }
            val meta = v.findViewById<TextView>(R.id.meta)
            meta.text = when {
                done == names.size -> getString(R.string.status_done)
                prog.any { it?.started == true } -> {
                    val last = store.lastPartOf(f.key, names)
                    getString(R.string.part_of, names.indexOf(last) + 1, names.size)
                }
                names.size > 1 -> getString(R.string.parts_size, names.size, Fmt.size(f.size))
                else -> Fmt.size(f.size)
            }
            val bar = v.findViewById<SegmentBar>(R.id.bar)
            bar.visibility = if (prog.any { it?.started == true }) View.VISIBLE else View.GONE
            bar.setParts(prog.map { it?.fraction ?: 0f })
            Thumbs.loadFilm(v.findViewById<ImageView>(R.id.thumb), f, store.lastPartOf(f.key, names))
            v.setOnClickListener { openFilm(f) }
        }
    }
}
