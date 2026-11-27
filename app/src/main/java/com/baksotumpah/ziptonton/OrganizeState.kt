package com.baksotumpah.ziptonton

import android.net.Uri
import java.util.concurrent.atomic.AtomicBoolean

/** Status proses "Rapikan jadi ZIP" yang dibagi antara layar dan layanan latar belakang (satu proses aplikasi). */
object OrganizeState {
    class Job(val tree: Uri, val groups: List<Group>, val deleteOriginals: Boolean, val outDir: java.io.File)

    @Volatile var pending: Job? = null
    @Volatile var running = false
    @Volatile var status = ""
    @Volatile var fileLine = ""
    @Volatile var progress = 0          // 0..1000
    @Volatile var result: RunResult? = null
    @Volatile var onChange: (() -> Unit)? = null
    val cancel = AtomicBoolean(false)

    fun changed() { onChange?.invoke() }
}
