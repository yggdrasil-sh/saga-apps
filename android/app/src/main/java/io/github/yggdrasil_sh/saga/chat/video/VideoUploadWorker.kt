package io.github.yggdrasil_sh.saga.chat.video

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.SagaApplication
import io.github.yggdrasil_sh.saga.chat.ChannelPlainSource
import io.github.yggdrasil_sh.saga.chat.ChatFile
import io.github.yggdrasil_sh.saga.chat.ChatFormat
import io.github.yggdrasil_sh.saga.chat.ChatLimits
import io.github.yggdrasil_sh.saga.chat.ChatSession
import io.github.yggdrasil_sh.saga.chat.ChatThumb
import io.github.yggdrasil_sh.saga.chat.FileUploaded
import io.github.yggdrasil_sh.saga.chat.FileV2
import io.github.yggdrasil_sh.saga.chat.FileV2Writer
import io.github.yggdrasil_sh.saga.data.SagaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Fehler, den die Person sehen soll (Text-Ressource + Zahl, z. B. MB) */
class VideoSendError(val res: Int, val arg: Long? = null) : Exception("video $res")

/**
 * Videos senden im Hintergrund (2.4): WorkManager (expedited, Mitteilung "wird hochgeladen" mit Fortschritt und
 * Abbrechen). Je Video eine eigene Nachricht. Ablauf: vorbereiten (Metadaten, Poster, "optimiert 720p") -> Poster als
 * v1 -> Video bis file_max als v1, sonst saga-file/v2 in Teilen (fortsetzbar nach Netzabbruch/Neustart) -> senden.
 * Ein Auftrag nach dem anderen (eine Warteschlange): hoechstens ein Export und ein Upload je Geraet.
 */
class VideoUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val jobId = params.inputData.getString(KEY_JOB).orEmpty()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }
    private var lastNote = 0L
    private var phase = PHASE_PREPARE

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(0f, null)

    private fun foreground(p: Float, name: String?): ForegroundInfo {
        val ctx = applicationContext
        Videos.channel(ctx)
        val cancel = WorkManager.getInstance(ctx).createCancelPendingIntent(id)
        val n = NotificationCompat.Builder(ctx, Videos.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(if (phase == PHASE_PREPARE) R.string.video_preparing else R.string.video_uploading))
            .setContentText(name)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, (p * 100).toInt(), p <= 0f)
            .addAction(0, ctx.getString(io.github.yggdrasil_sh.saga.R.string.action_cancel), cancel)
            .build()
        val nid = NOTE_BASE + (jobId.hashCode() and 0xfff)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(nid, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(nid, n)
    }

    /** Fortschritt (0..1) an die Oberflaeche und - hoechstens jede Sekunde - in die Mitteilung */
    private fun progress(p: Float, name: String) {
        setProgressAsync(workDataOf(KEY_PROGRESS to p, KEY_PHASE to phase))
        val now = System.currentTimeMillis()
        if (now - lastNote > 1000) {
            lastNote = now
            try {
                setForegroundAsync(foreground(p, name))
            } catch (_: Exception) {
            }
        }
    }

    override suspend fun doWork(): Result {
        val repo = (applicationContext as SagaApplication).repo
        val jobs = VideoJobs(repo.secure)
        var job = jobs.get(jobId) ?: return Result.failure()
        try {
            setForeground(foreground(0f, job.name))
        } catch (_: Exception) {
            // Vordergrund nicht erlaubt (z. B. Hintergrund-Start): laeuft trotzdem als normale Arbeit weiter
        }
        return try {
            job = run(repo, jobs, job)
            jobs.remove(job.id)
            cleanupFiles(job)
            Result.success()
        } catch (e: CancellationException) {
            // nur bei "Abbrechen" (Person) aufraeumen - bei Stopp durch das System (Netz weg, Kontingent) spaeter weiter
            if (Build.VERSION.SDK_INT >= 31 && stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) {
                withContext(NonCancellable) { abort(repo, jobs, job) }
            }
            throw e
        } catch (e: VideoSendError) {
            withContext(NonCancellable) { abort(repo, jobs, job) }
            Result.failure(workDataOf(KEY_ERROR to e.res, KEY_ERROR_ARG to (e.arg ?: -1L)))
        } catch (e: UploadRejected) {
            // zu viele gleichzeitige Uploads bzw. zu schnell: spaeter erneut
            if (e.code == 429 && runAttemptCount < MAX_ATTEMPTS) return Result.retry()
            withContext(NonCancellable) { abort(repo, jobs, job) }
            Result.failure(workDataOf(KEY_ERROR to Videos.errorFor(e), KEY_TEXT to (e.error ?: "")))
        } catch (e: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else {
                withContext(NonCancellable) { abort(repo, jobs, job) }
                Result.failure(workDataOf(KEY_ERROR to R.string.video_send_failed))
            }
        } catch (e: Exception) {
            withContext(NonCancellable) { abort(repo, jobs, job) }
            Result.failure(workDataOf(KEY_ERROR to R.string.video_send_failed, KEY_TEXT to (e.message ?: "")))
        }
    }

    /** Abbrechen bzw. aufgeben: Upload beim Server verwerfen, Auftrag und Dateien loeschen */
    private suspend fun abort(repo: SagaRepository, jobs: VideoJobs, job: VideoJob) {
        val cur = jobs.get(job.id) ?: job
        cur.upload?.let { if (cur.fileId == null) ApiUploadHttp(repo.api).delete(it) }
        jobs.remove(cur.id)
        cleanupFiles(cur)
    }

    private fun cleanupFiles(job: VideoJob) {
        job.file?.let { File(it).delete() }
    }

    private suspend fun limits(repo: SagaRepository): ChatLimits {
        if (repo.chat.me.value == null) repo.chat.refresh()
        return repo.chat.me.value?.limits ?: throw IOException("chat/me")
    }

    private suspend fun run(repo: SagaRepository, jobs: VideoJobs, start: VideoJob): VideoJob {
        var job = start
        val ctx = applicationContext
        val svc = repo.chat
        val limits = limits(repo)
        val session = withContext(Dispatchers.Main) { ChatSession(job.chatId, svc).also { it.loadSummary() } }
        val cid = session.fileCryptoId

        // 1. vorbereiten: Metadaten + Poster (Original), "optimiert 720p", faststart pruefen
        if (job.size == null) {
            phase = PHASE_PREPARE
            progress(0f, job.name)
            val src = job.uri.toUri()
            val probed = withContext(Dispatchers.IO) { VideoMedia.probe(ctx, src) }
            var info = probed?.first
            val poster = probed?.second
            var file: File? = null
            if (job.optimize && info != null) {
                val (sw, sh) = VideoLogic.shown(info.w, info.h, info.rot)
                val target = VideoLogic.target720(sw, sh, info.bitrate)
                if (target != null) {
                    val out = File(File(ctx.noBackupFilesDir, OUT_DIR), job.id + ".mp4")
                    try {
                        VideoMedia.optimize(ctx, src, target, out) { p -> progress(p * 0.95f, job.name) }
                        file = out
                        info = withContext(Dispatchers.IO) { VideoMedia.probe(ctx, Uri.fromFile(out), withPoster = false) }?.first ?: info
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        out.delete()                                  // Verkleinern ging nicht: Original senden
                    }
                }
            }
            val size = withContext(Dispatchers.IO) {
                file?.length() ?: ctx.contentResolver.openFileDescriptor(src, "r")?.use { it.statSize }?.takeIf { it >= 0 }
                    ?: throw VideoSendError(R.string.chat_file_broken)
            }
            val fs = withContext(Dispatchers.IO) {
                try {
                    (file?.let { FileInputStream(it) } ?: ctx.contentResolver.openInputStream(src))?.use { VideoLogic.moovFirst(it.buffered()) }
                } catch (_: Exception) {
                    null
                }
            }
            // Poster sofort als v1-Anhang (eigener Schluessel) - auch aeltere Apps zeigen es
            val th = poster?.let { p ->
                val (blob, key) = withContext(Dispatchers.Default) { svc.encryptFileAny(cid, p.jpeg) }
                val tid = json.decodeFromString<FileUploaded>(repo.api.postBytes("api/chats/${job.chatId}/files", blob)).id
                withContext(Dispatchers.IO) { svc.blobs.put(io.github.yggdrasil_sh.saga.chat.ChatBlobCache.key(job.chatId, tid), blob) }
                ChatThumb(tid, ChatFormat.b64(key), "image/jpeg", p.w, p.h, p.jpeg.size.toLong())
            }
            job = job.copy(
                file = file?.absolutePath, size = size, dur = info?.let { VideoLogic.durSeconds(it.durMs) },
                w = info?.w?.takeIf { it > 0 }, h = info?.h?.takeIf { it > 0 }, rot = info?.rot, fs = fs, th = th, noPoster = th == null,
            )
            jobs.put(job)
        }
        val size = job.size ?: throw VideoSendError(R.string.chat_file_broken)

        // 2. Weg pruefen (Grenzen vom Server, Gegenueber mit Saga 2.3)
        when (VideoLogic.route(size, limits, session.summary?.bigFiles)) {
            VideoLogic.Route.NO_FILE2 -> throw VideoSendError(R.string.video_too_big_server, limits.fileMax / 1048576)
            VideoLogic.Route.PEER_OLD -> throw VideoSendError(R.string.video_peer_old, limits.fileMax / 1048576)
            VideoLogic.Route.TOO_BIG -> throw VideoSendError(R.string.chat_file_too_big, limits.videoMax / 1048576)
            VideoLogic.Route.V1 -> if (job.fileId == null) {
                phase = PHASE_UPLOAD
                progress(0f, job.name)
                val data = withContext(Dispatchers.IO) { openPlain(job).use { it.readBytes() } }
                if (data.size.toLong() != size) throw VideoSendError(R.string.chat_file_broken)
                val (blob, key) = withContext(Dispatchers.Default) { svc.encryptFileAny(cid, data) }
                val id = json.decodeFromString<FileUploaded>(repo.api.postBytes("api/chats/${job.chatId}/files", blob)).id
                job = job.copy(fileId = id, key = ChatFormat.b64(key), x = 1)
                jobs.put(job)
                progress(1f, job.name)
            }
            VideoLogic.Route.V2 -> if (job.fileId == null) job = uploadV2(repo, jobs, job, limits, size)
        }

        // 3. senden (eigene Nachricht mit Video + Poster)
        val f = ChatFile(
            id = job.fileId!!, k = job.key!!, n = job.name, m = job.mime.ifEmpty { "video/mp4" }, s = size,
            w = job.w, h = job.h, th = job.th, x = job.x?.takeIf { it == 2 }, cs = if (job.x == 2) FileV2.CS else null,
            dur = job.dur, rot = job.rot, fs = job.fs,
        )
        withContext(Dispatchers.Main) { session.sendFiles(listOf(f)) }
        return job
    }

    private fun openPlain(job: VideoJob) = job.file?.let { FileInputStream(it) }
        ?: applicationContext.contentResolver.openInputStream(job.uri.toUri()) ?: throw VideoSendError(R.string.chat_file_broken)

    private suspend fun uploadV2(repo: SagaRepository, jobs: VideoJobs, start: VideoJob, limits: ChatLimits, size: Long): VideoJob {
        var job = start
        phase = PHASE_UPLOAD
        val crypto = repo.chat.crypto
        if (job.key == null || job.prefix == null) {
            job = job.copy(key = ChatFormat.b64(crypto.keygen()), prefix = ChatFormat.hex(crypto.random(16)), upload = null, url = null)
            jobs.put(job)
        }
        val key = ChatFormat.unb64(job.key, 32) ?: throw VideoSendError(R.string.chat_file_broken)
        val prefix = ChatFormat.unhex(job.prefix!!)
        val http = ApiUploadHttp(repo.api)
        val pfd = withContext(Dispatchers.IO) {
            job.file?.let { android.os.ParcelFileDescriptor.open(File(it), android.os.ParcelFileDescriptor.MODE_READ_ONLY) }
                ?: applicationContext.contentResolver.openFileDescriptor(job.uri.toUri(), "r")
                ?: throw VideoSendError(R.string.chat_file_broken)
        }
        pfd.use { fd ->
            FileInputStream(fd.fileDescriptor).use { fin ->
                val src = ChannelPlainSource(fin.channel, size)
                val writer = FileV2Writer(crypto, key, prefix, src, FileV2.CS)
                for (attempt in 0..1) {
                    var start0 = 0L
                    if (job.upload == null) {
                        val c = http.create(job.chatId, writer.blobSize)
                        job = job.copy(upload = c.upload, url = c.url)
                        jobs.put(job)
                        start0 = c.offset
                    }
                    val part = minOf(limits.uploadPart.takeIf { it > 0 } ?: (8L shl 20), 8L shl 20).coerceAtLeast(64L * 1024)
                    val up = V2Uploader(http, writer, part)
                    try {
                        if (start0 == 0L && attempt == 0 && start.upload != null) start0 = up.resumeOffset(job.url!!, job.upload!!) ?: 0L
                        val id = withContext(Dispatchers.IO) {
                            up.upload(job.upload!!, job.url!!, start0) { off, total -> progress(off.toFloat() / total, job.name) }
                        }
                        job = job.copy(fileId = id, x = 2)
                        jobs.put(job)
                        return job
                    } catch (_: UploadGone) {
                        // abgelaufen (24 h) oder verworfen: einmal neu anlegen
                        job = job.copy(upload = null, url = null)
                        jobs.put(job)
                    }
                }
            }
        }
        throw VideoSendError(R.string.video_send_failed)
    }

    companion object {
        const val KEY_JOB = "job"
        const val KEY_PROGRESS = "p"
        const val KEY_PHASE = "phase"
        const val KEY_ERROR = "err"
        const val KEY_ERROR_ARG = "arg"
        const val KEY_TEXT = "text"
        const val PHASE_PREPARE = 0
        const val PHASE_UPLOAD = 1
        const val OUT_DIR = "video-out"
        const val MAX_ATTEMPTS = 8
        private const val NOTE_BASE = 0x5a000
    }
}

/** Einstieg fuer die Oberflaeche: Auftraege anlegen, beobachten, abbrechen, beim Abmelden alles verwerfen */
object Videos {
    const val CHANNEL = "video_upload"
    const val TAG = "saga-video"
    private const val QUEUE = "saga-video-queue"
    const val TAG_CHAT = "chat:"
    const val TAG_NAME = "name:"
    const val TAG_JOB = "job:"

    /** in diesem Prozess laufend gesehene bzw. schon gemeldete Auftraege (Hinweis "fertig/Fehler" nur einmal) */
    val seenRunning: MutableSet<UUID> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    val reported: MutableSet<UUID> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.video_channel), NotificationManager.IMPORTANCE_LOW))
        }
    }

    fun tagChat(chatId: Long) = TAG_CHAT + chatId

    /** Video zum Senden einreihen (eigene Nachricht) */
    fun enqueue(ctx: Context, repo: SagaRepository, chatId: Long, uri: Uri, name: String, mime: String, optimize: Boolean) {
        // Zugriff auf die Quelle ueber einen App-Neustart hinaus behalten (nur bei Dokument-Uris moeglich)
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        val job = VideoJob(UUID.randomUUID().toString(), chatId, uri.toString(), name.take(200), mime, optimize, System.currentTimeMillis())
        VideoJobs(repo.secure).put(job)
        val req = OneTimeWorkRequestBuilder<VideoUploadWorker>()
            .setInputData(workDataOf(VideoUploadWorker.KEY_JOB to job.id))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(TAG).addTag(tagChat(chatId)).addTag(TAG_JOB + job.id).addTag(TAG_NAME + job.name)
            .build()
        seenRunning.add(req.id)
        WorkManager.getInstance(ctx).enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    fun cancel(ctx: Context, id: UUID) {
        WorkManager.getInstance(ctx).cancelWorkById(id)
    }

    fun nameOf(info: WorkInfo): String = info.tags.firstOrNull { it.startsWith(TAG_NAME) }?.removePrefix(TAG_NAME).orEmpty()

    /**
     * Auftraege ohne laufende Arbeit (abgebrochen ueber die Mitteilung, Prozess beendet waehrend des Abbruchs):
     * Upload beim Server verwerfen, Auftrag und Zwischendatei loeschen. Beim Oeffnen eines Chats.
     */
    suspend fun sweep(ctx: Context, repo: SagaRepository) {
        val jobs = VideoJobs(repo.secure)
        val ids = jobs.ids()
        if (ids.isEmpty()) return
        val live = withContext(Dispatchers.IO) {
            WorkManager.getInstance(ctx).getWorkInfosByTag(TAG).get()
        }.filter { !it.state.isFinished }.flatMap { w -> w.tags.filter { it.startsWith(TAG_JOB) }.map { it.removePrefix(TAG_JOB) } }.toSet()
        val now = System.currentTimeMillis()
        for (id in ids) {
            if (id in live) continue
            val j = jobs.get(id)
            if (j != null && now - j.created < 120_000) continue
            jobs.remove(id)
            j?.file?.let { File(it).delete() }
            j?.upload?.let { if (j.fileId == null) ApiUploadHttp(repo.api).delete(it) }
        }
    }

    /** Abmelden/Kontowechsel: alle Auftraege abbrechen und verwerfen, Zwischendateien und Video-Cache loeschen */
    fun clearAll(ctx: Context, repo: SagaRepository) {
        try {
            WorkManager.getInstance(ctx).cancelAllWorkByTag(TAG)
        } catch (_: Exception) {
        }
        val jobs = VideoJobs(repo.secure)
        jobs.ids().forEach { jobs.remove(it) }
        File(ctx.noBackupFilesDir, VideoUploadWorker.OUT_DIR).deleteRecursively()
        VideoCache.clear(ctx)
    }

    /** Text fuer eine Ablehnung des Servers */
    fun errorFor(e: UploadRejected): Int = when (e.code) {
        409 -> R.string.video_peer_old_short
        413 -> R.string.video_quota
        429 -> R.string.video_busy
        else -> R.string.video_send_failed
    }
}
