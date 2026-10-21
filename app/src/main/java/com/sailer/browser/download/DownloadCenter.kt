package com.sailer.browser.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cola de descargas: como mucho 2 a la vez, siguen aunque cierres el
 * navegador, se pueden pausar y reanudar, y se reintentan solas si se corta
 * la conexión.
 */
object DownloadCenter {

    enum class Status { QUEUED, RUNNING, PAUSED, FAILED }

    class Task(val id: Int, val request: DownloadRequest, workDir: File) {
        @Volatile var status = Status.QUEUED
        @Volatile var progress = -1
        @Volatile var error: String? = null
        @Volatile var retrying = false
        internal val state = DownloadState(workDir)
        @Volatile internal var pauseRequested = false
        internal var job: Job? = null
    }

    fun interface Listener {
        fun onDownloadsChanged()
    }

    private lateinit var appContext: Context
    private lateinit var workRoot: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(2)
    private val main = Handler(Looper.getMainLooper())
    private val nextId = AtomicInteger(1)
    private val _tasks = CopyOnWriteArrayList<Task>()
    private val listeners = CopyOnWriteArraySet<Listener>()

    /** Descargas en curso, en pausa o fallidas (las terminadas pasan a la carpeta segura). */
    val tasks: List<Task> get() = _tasks.toList()

    /** Descargas que mantienen el servicio en marcha (en cola o bajando). */
    val activeCount: Int get() = _tasks.count { it.status == Status.QUEUED || it.status == Status.RUNNING }

    val pausedCount: Int get() = _tasks.count { it.status == Status.PAUSED }

    fun init(context: Context) {
        appContext = context.applicationContext
        workRoot = File(appContext.filesDir, "downloads")
        // Restos de una sesión anterior (la app se cerró a medias).
        workRoot.deleteRecursively()
    }

    fun enqueue(request: DownloadRequest): Task {
        val id = nextId.getAndIncrement()
        val task = Task(id, request, File(workRoot, id.toString()))
        _tasks.add(task)
        start(task)
        return task
    }

    fun pause(task: Task) {
        if (task.status != Status.RUNNING && task.status != Status.QUEUED) return
        task.pauseRequested = true
        task.status = Status.PAUSED
        task.job?.cancel()
        notifyChanged()
    }

    fun resume(task: Task) {
        if (task.status != Status.PAUSED && task.status != Status.FAILED) return
        task.error = null
        start(task)
    }

    fun pauseAll() = _tasks.forEach { pause(it) }

    fun resumeAll() = _tasks.filter { it.status == Status.PAUSED }.forEach { resume(it) }

    fun cancel(task: Task) {
        task.job?.cancel()
        _tasks.remove(task)
        scope.launch { task.state.clear() }
        notifyChanged()
    }

    fun addListener(listener: Listener) = listeners.add(listener)

    fun removeListener(listener: Listener) = listeners.remove(listener)

    private fun start(task: Task) {
        task.pauseRequested = false
        task.status = Status.QUEUED
        DownloadService.start(appContext)
        task.job = scope.launch {
            semaphore.withPermit { run(task) }
        }
        notifyChanged()
    }

    private suspend fun run(task: Task) {
        task.status = Status.RUNNING
        notifyChanged()
        try {
            var attempt = 0
            while (true) {
                try {
                    Downloader(appContext).download(task.request, task.state) { percent ->
                        task.progress = percent
                        task.retrying = false
                        notifyChanged()
                    }
                    break
                } catch (e: IOException) {
                    // Sin conexión o servidor caído: esperar y seguir donde se quedó.
                    if (e is HlsDownloader.DrmException || attempt >= RETRY_DELAYS.size) throw e
                    task.retrying = true
                    task.error = e.message
                    notifyChanged()
                    delay(RETRY_DELAYS[attempt++])
                }
            }
            _tasks.remove(task)
            task.state.clear()
        } catch (e: CancellationException) {
            if (!task.pauseRequested) {
                _tasks.remove(task)
                task.state.clear()
            }
            throw e
        } catch (e: Throwable) {
            task.status = Status.FAILED
            task.error = e.message ?: e.javaClass.simpleName
        } finally {
            task.retrying = false
            notifyChanged()
        }
    }

    private fun notifyChanged() {
        main.post { listeners.forEach { it.onDownloadsChanged() } }
    }

    private val RETRY_DELAYS = longArrayOf(3_000, 10_000, 30_000, 60_000)
}
