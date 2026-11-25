package com.baksotumpah.ziptonton

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/** Halaman satu film (satu ZIP): cuplikan, tombol lanjut, dan daftar part. */
class FilmActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_KEY = "key"
    }

    private lateinit var store: Store
    private lateinit var key: String
    private lateinit var uri: Uri
    private var film: Film? = null
    private val parts = mutableListOf<ZipItem>()
    private val adapter = PartAdapter()
    private lateinit var touchHelper: ItemTouchHelper
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_film)
        store = Store(this)
        uri = Uri.parse(intent.getStringExtra(EXTRA_URI) ?: run { finish(); return })
        key = intent.getStringExtra(EXTRA_KEY) ?: run { finish(); return }

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.more).setOnClickListener { v ->
            PopupMenu(this, v).apply {
                menu.add(0, 1, 0, R.string.reset_progress)
                menu.add(0, 2, 1, R.string.reset_order)
                setOnMenuItemClickListener { item ->
                    if (item.itemId == 2) {
                        store.clearOrder(key)
                        film?.let { parts.clear(); parts.addAll(it.videos.sortedWith(ZipIndex.naturalOrder)) }
                        adapter.notifyDataSetChanged()
                    } else {
                        MaterialAlertDialogBuilder(this@FilmActivity)
                            .setTitle(R.string.reset_title)
                            .setMessage(R.string.reset_msg)
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.reset) { _, _ -> store.resetFilm(key); adapter.notifyDataSetChanged() }
                            .show()
                    }
                    true
                }
                show()
            }
        }
        val list = findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@FilmActivity)
            adapter = this@FilmActivity.adapter
        }
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun isLongPressDragEnabled() = false
            override fun isItemViewSwipeEnabled() = false
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
                if (vh.bindingAdapterPosition <= 0) 0 else makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition; val to = target.bindingAdapterPosition
                if (from <= 0 || to <= 0) return false
                parts.add(to - 1, parts.removeAt(from - 1))
                adapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                store.saveOrder(key, parts.map { it.name })
                rv.post { adapter.notifyDataSetChanged() }   // perbarui nomor & bar progres
            }
        })
        touchHelper.attachToRecyclerView(list)

        io.execute {
            val f = try {
                val videos = Library.loadVideos(this, uri)
                val name = key.substringAfterLast('/')
                Film(key, uri, name.substringBeforeLast('.'), 0L, 0L, videos)
            } catch (_: Exception) { null }
            runOnUiThread {
                if (f == null || f.videos.isEmpty()) { finish(); return@runOnUiThread }
                film = f
                parts.clear(); parts.addAll(store.applyOrder(key, f.videos))
                adapter.notifyDataSetChanged()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged()
    }

    private fun play(entry: String) {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_URI, uri.toString())
            .putExtra(PlayerActivity.EXTRA_KEY, key)
            .putExtra(PlayerActivity.EXTRA_ENTRY, entry))
    }

    inner class PartAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = if (film != null) parts.size + 1 else 0
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = if (viewType == 0) R.layout.row_film_header else R.layout.item_part
            return object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false)) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val f = film ?: return
            val v = h.itemView
            if (position == 0) bindHeader(v, f) else bindPart(h, v, f, parts[position - 1], position)
        }

        private fun bindHeader(v: View, f: Film) {
            val names = parts.map { it.name }
            val totalSize = parts.sumOf { it.size }
            v.findViewById<TextView>(R.id.title).text = f.title
            v.findViewById<TextView>(R.id.initial).text = f.title.take(1).uppercase()
            v.findViewById<TextView>(R.id.meta).text =
                if (names.size > 1) getString(R.string.parts_size, names.size, Fmt.size(totalSize)) else Fmt.size(totalSize)
            v.findViewById<SegmentBar>(R.id.bar).setParts(names.map { store.progress(f.key, it)?.fraction ?: 0f })
            val target = store.lastPartOf(f.key, names) ?: names.first()
            val idx = names.indexOf(target)
            val p = store.progress(f.key, target)
            val btn = v.findViewById<MaterialButton>(R.id.primary)
            btn.text = when {
                p != null && !p.finished && p.positionMs > 0 ->
                    if (names.size > 1) getString(R.string.continue_part_at, idx + 1, Fmt.time(p.positionMs))
                    else getString(R.string.continue_at, Fmt.time(p.positionMs))
                names.size > 1 -> getString(R.string.play_part, idx + 1)
                else -> getString(R.string.play)
            }
            btn.setOnClickListener { play(target) }
            Thumbs.loadFilm(v.findViewById<ImageView>(R.id.backdrop), f, target)
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun bindPart(h: RecyclerView.ViewHolder, v: View, f: Film, item: ZipItem, number: Int) {
            val p = store.progress(f.key, item.name)
            v.findViewById<TextView>(R.id.title).text = Fmt.stripExt(item.name)
            v.findViewById<TextView>(R.id.number).text = number.toString()
            v.findViewById<TextView>(R.id.meta).text = when {
                p == null || !p.started -> getString(R.string.not_watched, Fmt.size(item.size))
                p.finished -> getString(R.string.status_done)
                else -> Fmt.remaining(p.positionMs, p.durationMs)
            }
            val dur = v.findViewById<TextView>(R.id.duration)
            dur.visibility = if (p != null && p.durationMs > 0) View.VISIBLE else View.GONE
            dur.text = p?.let { Fmt.time(it.durationMs) }
            val bar = v.findViewById<SegmentBar>(R.id.bar)
            bar.visibility = if (p?.started == true) View.VISIBLE else View.GONE
            bar.setParts(listOf(p?.fraction ?: 0f))
            Thumbs.loadPart(v.findViewById(R.id.thumb), f, item)
            v.setOnClickListener { play(item.name) }
            v.findViewById<View>(R.id.handle).setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(h)
                true
            }
        }
    }
}
