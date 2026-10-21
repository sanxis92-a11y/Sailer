package com.sailer.browser.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/** Cola de descargas: como mucho 2 a la vez, siguen aunque salgas de la app. */
object DownloadCenter {

    enum class Status { QUEUED, RUNNING, FAILED }

    class Task(val id: Int, val request: DownloadRequest) {
        @Volatile var status = Status.QUEUED
        @Volatile var progress = -1
        @Volatile var error: String? = null
        internal var job: Job? = null
    }

    fun interface Listener {
        fun onDownloadsChanged()
    }

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(2)
    private val main = Handler(Looper.getMainLooper())
    private val nextId = AtomicInteger(1)
    private val _tasks = CopyOnWriteArrayList<Task>()
    private val listeners = CopyOnWriteArraySet<Listener>()

    /** Descargas en curso, en cola o fallidas (las terminadas pasan a la carpeta segura). */
    val tasks: List<Task> get() = _tasks.toList()

    val activeCount: Int get() = _tasks.count { it.status != Status.FAILED }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun enqueue(request: DownloadRequest): Task {
        val task = Task(nextId.getAndIncrement(), request)
        _tasks.add(task)
        start(task)
        return task
    }

    fun retry(task: Task) {
        task.status = Status.QUEUED
        task.error = null
        task.progress = -1
        start(task)
    }

    fun cancel(task: Task) {
        task.job?.cancel()
        _tasks.remove(task)
        notifyChanged()
    }

    fun addListener(listener: Listener) = listeners.add(listener)

    fun removeListener(listener: Listener) = listeners.remove(listener)

    private fun start(task: Task) {
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
            Downloader(appContext).download(task.request) { percent ->
                task.progress = percent
                notifyChanged()
            }
            _tasks.remove(task)
        } catch (e: CancellationException) {
            _tasks.remove(task)
            throw e
        } catch (e: Throwable) {
            task.status = Status.FAILED
            task.error = e.message ?: e.javaClass.simpleName
        } finally {
            notifyChanged()
        }
    }

    private fun notifyChanged() {
        main.post { listeners.forEach { it.onDownloadsChanged() } }
    }
}
