package com.baksotumpah.ziptonton

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/**
 * Rapikan folder berisi film yang bercampur: kelompokkan per judul + musim, satu ZIP per kelompok
 * (tanpa kompresi), periksa ZIP-nya, lalu (opsional) hapus file aslinya.
 * Prosesnya dijalankan OrganizeService di latar belakang; layar ini hanya menampilkan statusnya.
 */
class OrganizeActivity : AppCompatActivity() {

    private var tree: Uri? = null
    private val groups = mutableListOf<Group>()
    private var skipped = 0
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

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
                OrganizeState.running -> {}
                groups.isEmpty() -> pick.launch(null)
                else -> confirmStart()
            }
        }
        cancelBtn.setOnClickListener { OrganizeState.cancel.set(true); cancelBtn.isEnabled = false }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (OrganizeState.running) Toast.makeText(this@OrganizeActivity, R.string.org_back_bg, Toast.LENGTH_SHORT).show()
                finish()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        OrganizeState.onChange = { runOnUiThread { render() } }
        render()
    }

    override fun onStop() {
        OrganizeState.onChange = null
        super.onStop()
    }

    /** Selaraskan tampilan dengan status layanan (berjalan / hasil akhir). */
    private fun render() {
        val st = OrganizeState
        if (st.running) {
            showRunningUi()
            status.text = st.status
            fileLine.text = st.fileLine
            bar.progress = st.progress
            return
        }
        val r = st.result ?: return
        st.result = null   // hasil hanya ditampilkan sekali
        cancelBtn.visibility = View.GONE
        bar.visibility = View.GONE
        status.visibility = View.VISIBLE
        fileLine.visibility = View.VISIBLE
        status.text = getString(if (r.cancelled) R.string.org_cancelled else R.string.org_done, r.zipsMade, r.originalsDeleted)
        fileLine.text = if (r.failures.isEmpty()) "" else getString(R.string.org_failures, r.failures.joinToString("\n"))
        groups.clear()
        adapter.notifyDataSetChanged()
        primary.visibility = View.VISIBLE
        primary.isEnabled = true
        primary.setText(R.string.edit_done)
        primary.setOnClickListener { finish() }
    }

    private fun showRunningUi() {
        primary.visibility = View.GONE
        cancelBtn.visibility = View.VISIBLE
        cancelBtn.isEnabled = !OrganizeState.cancel.get()
        deleteBox.visibility = View.GONE
        status.visibility = View.VISIBLE
        fileLine.visibility = View.VISIBLE
        bar.visibility = View.VISIBLE
        info.setText(R.string.org_bg_hint)
    }

    private fun scan(uri: Uri) {
        tree = uri
        info.setText(R.string.org_scanning)
        primary.isEnabled = false
        io.execute {
            val result = try {
                Organizer.plan(Organizer.listVideos(this, uri))
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
        s += "\n" + if (g.needsSubtitles) getString(R.string.org_dest_folder, g.folderName) else getString(R.string.org_dest_root)
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

    private val storagePerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private fun askStorage() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.storage_title)
            .setMessage(R.string.org_need_storage)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.storage_allow) { _, _ ->
                val i = StorageAccess.settingsIntent(this)
                if (i != null) try { startActivity(i) } catch (_: Exception) {}
                else storagePerms.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            }
            .show()
    }

    private fun start(sel: List<Group>, deleteOriginals: Boolean) {
        val t = tree ?: return
        if (!StorageAccess.hasAccess(this)) { askStorage(); return }
        val outDir = StorageAccess.ensureDir()
        if (outDir == null) { Toast.makeText(this, R.string.storage_failed, Toast.LENGTH_LONG).show(); return }
        // Izin folder dipertahankan selama proses latar belakang berjalan; izin tulis dilepas setelah selesai.
        try {
            contentResolver.takePersistableUriPermission(t, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        OrganizeState.cancel.set(false)
        OrganizeState.pending = OrganizeState.Job(t, sel, deleteOriginals, outDir)
        ContextCompat.startForegroundService(this, Intent(this, OrganizeService::class.java))
        showRunningUi()
        status.text = getString(R.string.org_notif_start)
        fileLine.text = ""
        bar.progress = 0
        adapter.notifyDataSetChanged()
    }

    override fun onDestroy() {
        io.shutdown()   // proses latar belakang tidak ikut berhenti
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
            check.isEnabled = !OrganizeState.running
            check.setOnCheckedChangeListener { _, c -> g.selected = c; updateSummary() }
            v.setOnClickListener { if (!OrganizeState.running) check.toggle() }
        }
    }
}
