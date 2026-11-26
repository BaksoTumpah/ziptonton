package com.baksotumpah.ziptonton

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rapikan folder berisi film yang bercampur: kelompokkan per judul + musim, satu ZIP per kelompok
 * (tanpa kompresi), periksa ZIP-nya, lalu (opsional) hapus file aslinya.
 */
class OrganizeActivity : AppCompatActivity() {

    private var tree: Uri? = null
    private val groups = mutableListOf<Group>()
    private var skipped = 0
    private var running = false
    private val cancelFlag = AtomicBoolean(false)
    private val io = Executors.newSingleThreadExecutor()
    private val adapter = GroupAdapter()

    private lateinit var info: TextView
    private lateinit var status: TextView
    private lateinit var fileLine: TextView
    private lateinit var bar: ProgressBar
    private lateinit var deleteBox: CheckBox
    private lateinit var primary: MaterialButton
    private lateinit var cancelBtn: MaterialButton

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scan(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_organize)
        info = findViewById(R.id.info)
        status = findViewById(R.id.status)
        fileLine = findViewById(R.id.fileLine)
        bar = findViewById(R.id.bar)
        deleteBox = findViewById(R.id.deleteBox)
        primary = findViewById(R.id.primary)
        cancelBtn = findViewById(R.id.cancelBtn)
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@OrganizeActivity)
            adapter = this@OrganizeActivity.adapter
        }
        primary.setOnClickListener {
            when {
                running -> {}
                groups.isEmpty() -> pick.launch(null)
                else -> confirmStart()
            }
        }
        cancelBtn.setOnClickListener { cancelFlag.set(true); cancelBtn.isEnabled = false }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (running) Toast.makeText(this@OrganizeActivity, R.string.org_back_busy, Toast.LENGTH_SHORT).show()
                else finish()
            }
        })
    }

    private fun scan(uri: Uri) {
        tree = uri
        info.setText(R.string.org_scanning)
        primary.isEnabled = false
        io.execute {
            val result = try {
                val files = Organizer.listVideos(this, uri)
                Organizer.plan(files)
            } catch (_: Exception) { null }
            runOnUiThread {
                primary.isEnabled = true
                groups.clear()
                if (result != null) { groups.addAll(result.groups); skipped = result.skipped.size }
                adapter.notifyDataSetChanged()
                if (groups.isEmpty()) {
                    info.setText(R.string.org_none)
                    deleteBox.visibility = View.GONE
                    primary.setText(R.string.org_pick)
                } else {
                    deleteBox.visibility = View.VISIBLE
                    primary.setText(R.string.org_start)
                    updateSummary()
                }
            }
        }
    }

    private fun updateSummary() {
        val sel = groups.filter { it.selected }
        var text = getString(R.string.org_found, sel.size, sel.sumOf { it.items.size })
        if (skipped > 0) text += " " + getString(R.string.org_skipped, skipped)
        info.text = text
        primary.isEnabled = sel.isNotEmpty()
    }

    private fun describe(g: Group): String {
        val eps = g.items.map { it.episode }.sorted()
        var s = getString(R.string.org_group_meta, g.items.size, Fmt.size(g.totalSize))
        if (eps.isNotEmpty()) s += " · E" + Organizer.pad(eps.first()) + "–E" + Organizer.pad(eps.last())
        if (g.missing.isNotEmpty()) s += "\n" + getString(R.string.org_missing, g.missing.joinToString(", ") { "E" + Organizer.pad(it) })
        if (g.hasDuplicates) s += "\n" + getString(R.string.org_dups)
        return s
    }

    private fun confirmStart() {
        val sel = groups.filter { it.selected }
        if (sel.isEmpty()) return
        val del = deleteBox.isChecked
        val msg = if (del)
            getString(R.string.org_confirm_msg_delete, sel.size, sel.sumOf { it.items.size }, Fmt.size(sel.sumOf { it.totalSize }))
        else getString(R.string.org_confirm_msg_keep, sel.size)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.org_confirm_title)
            .setMessage(msg)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.org_start) { _, _ -> start(sel, del) }
            .show()
    }

    private val listener = object : Organizer.Listener {
        private var lastUi = 0L

        override fun onGroup(index: Int, total: Int, group: Group) {
            runOnUiThread { status.text = getString(R.string.org_running, index, total, group.zipName) }
        }

        override fun onProgress(file: String, fileDone: Long, fileSize: Long, doneAll: Long, totalAll: Long) {
            val now = SystemClock.uptimeMillis()
            if (now - lastUi < 250) return
            lastUi = now
            val pct = if (fileSize > 0) (fileDone * 100 / fileSize).toInt() else 0
            val overall = if (totalAll > 0) (doneAll * 1000 / totalAll).toInt() else 0
            runOnUiThread {
                fileLine.text = getString(R.string.org_file, file, pct)
                bar.progress = overall
            }
        }

        override fun onStatus(text: String) {
            runOnUiThread { fileLine.setText(R.string.org_verifying) }
        }

        override fun cancelled() = cancelFlag.get()
    }

    private fun start(sel: List<Group>, deleteOriginals: Boolean) {
        val t = tree ?: return
        running = true
        cancelFlag.set(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        primary.visibility = View.GONE
        cancelBtn.visibility = View.VISIBLE
        cancelBtn.isEnabled = true
        deleteBox.visibility = View.GONE
        status.visibility = View.VISIBLE
        fileLine.visibility = View.VISIBLE
        bar.visibility = View.VISIBLE
        bar.progress = 0
        adapter.notifyDataSetChanged()
        io.execute {
            val res = try {
                Organizer.run(this@OrganizeActivity, t, sel, deleteOriginals, listener)
            } catch (e: Exception) {
                RunResult(0, 0, listOf(e.message ?: e.javaClass.simpleName), false)
            }
            runOnUiThread { finishRun(res) }
        }
    }

    private fun finishRun(r: RunResult) {
        running = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cancelBtn.visibility = View.GONE
        bar.visibility = View.GONE
        status.text = getString(if (r.cancelled) R.string.org_cancelled else R.string.org_done, r.zipsMade, r.originalsDeleted)
        fileLine.text = if (r.failures.isEmpty()) "" else getString(R.string.org_failures, r.failures.joinToString("\n"))
        groups.clear()
        adapter.notifyDataSetChanged()
        primary.visibility = View.VISIBLE
        primary.isEnabled = true
        primary.setText(R.string.edit_done)
        primary.setOnClickListener { finish() }
    }

    override fun onDestroy() {
        cancelFlag.set(true)
        io.shutdown()
        super.onDestroy()
    }

    inner class GroupAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = groups.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false)) {}

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val g = groups[position]
            val v = h.itemView
            val check = v.findViewById<CheckBox>(R.id.check)
            v.findViewById<TextView>(R.id.name).text = g.zipName
            v.findViewById<TextView>(R.id.meta).text = describe(g)
            check.setOnCheckedChangeListener(null)
            check.isChecked = g.selected
            check.isEnabled = !running
            check.setOnCheckedChangeListener { _, c -> g.selected = c; updateSummary() }
            v.setOnClickListener { if (!running) check.toggle() }
        }
    }
}
