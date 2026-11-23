package com.hee.autosync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        private val running = Mutex() // 주기 실행과 "지금 실행"이 겹쳐 같은 파일을 두 번 보내지 않게
    }

    override suspend fun doWork(): Result {
        return running.withLock { withContext(Dispatchers.IO) { sync() } }
    }

    private suspend fun sync(): Result {
        val ctx = applicationContext
        val s = Settings(ctx)
        val manual = inputData.getBoolean("manual", false)
        val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        if (!manual && !(if (s.receiver) s.inDownloadWindow(now) else s.inUploadWindow(now))) {
            Status.note = "예약 시간 대기"; return Result.success()
        }
        if (!s.receiver && !s.askedDelete) { Status.note = "처음 질문에 답하면 시작"; return Result.success() }
        Status.cancelled = false
        Status.busy = true
        return try {
            Status.stage(0, "로그인 확인 중")
            if (Drive.token(ctx) == null) { Status.note = "로그인 필요"; return Result.success() }
            // 큰 영상이 10분 넘게 걸려도 안 끊기게. 안드로이드 12+ 백그라운드에서는 거부될 수 있어 실패해도 진행.
            try { setForeground(foregroundInfo()) } catch (_: Exception) {}
            val drive = Drive { Drive.token(ctx) ?: throw IllegalStateException("구글 로그인 필요") }
            Status.stage(1, "드라이브 접속 중")
            val folder = s.folderId ?: drive.folderId().also { s.folderId = it }
            if (s.receiver) DirectService.sync(ctx) // 꺼져 있으면 다시 염
            if (s.receiver) download(ctx, s, drive, folder) else upload(ctx, s, drive, folder, manual)
            Result.success()
        } catch (e: CancelledByUser) {
            Status.note = "취소됨"; Result.success()
        } catch (e: Exception) {
            if (e.message?.contains("404") == true) s.folderId = null // 드라이브 폴더가 지워졌으면 다음에 다시 만듦
            log(ctx, "오류: ${e.message}")
            Status.note = "오류, 다시 시도 대기"
            Result.retry()
        } finally {
            Status.busy = false
            Status.step = -1
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sync", "동기화", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, "sync")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(if (Settings(applicationContext).receiver) "받는 중" else "보내는 중")
            .setOngoing(true)
            .build()
        return ForegroundInfo(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}

private fun constraints(s: Settings) = Constraints.Builder()
    .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
    .setRequiresCharging(s.chargingOnly)
    .build()

/** 15분마다(안드로이드 최소 주기). 설정이 바뀌면 다시 불러서 조건 갱신. */
fun schedule(ctx: Context) {
    if (!Settings(ctx).auto) { WorkManager.getInstance(ctx).cancelUniqueWork("sync"); return }
    val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints(Settings(ctx))).build()
    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.UPDATE, req)
}

/** "지금 실행": 와이파이/충전 조건은 지키고, 예약 시간은 무시하고 바로 시작 */
fun syncNow(ctx: Context) = thread {
    val wm = WorkManager.getInstance(ctx)
    // 이미 도는 중이면 그대로. 아니면 새로 (전에 실패해서 재시도 대기 중인 것 뒤에 줄 서지 않게 교체)
    if (wm.getWorkInfosForUniqueWork("now").get().any { it.state == WorkInfo.State.RUNNING }) return@thread
    Status.note = "시작 중"
    val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints(Settings(ctx)))
        .setInputData(workDataOf("manual" to true)).build()
    wm.enqueueUniqueWork("now", ExistingWorkPolicy.REPLACE, req)
}

/** 취소하기: 옮기던 파일은 다음 진행 알림(64KB마다)에서 끊김. 폰 파일은 그대로. */
fun cancelSync() {
    if (Status.busy) { Status.cancelled = true; Status.note = "취소하는 중" }
}
