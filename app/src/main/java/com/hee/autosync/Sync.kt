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
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

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
        return try {
            if (Drive.token(ctx) == null) { log(ctx, "구글 로그인 필요"); return Result.success() }
            // 큰 영상이 10분 넘게 걸려도 안 끊기게. 안드로이드 12+ 백그라운드에서는 거부될 수 있어 실패해도 진행.
            try { setForeground(foregroundInfo()) } catch (_: Exception) {}
            val drive = Drive { Drive.token(ctx) ?: throw IllegalStateException("구글 로그인 필요") }
            val folder = s.folderId ?: drive.folderId().also { s.folderId = it }
            if (s.receiver) download(ctx, s, drive, folder) else upload(ctx, s, drive, folder)
            Result.success()
        } catch (e: Exception) {
            if (e.message?.contains("404") == true) s.folderId = null // 드라이브 폴더가 지워졌으면 다음에 다시 만듦
            log(ctx, "오류: ${e.message}")
            Result.retry()
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
    val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints(Settings(ctx))).build()
    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.UPDATE, req)
}

/** "지금 실행": 와이파이/충전 조건은 지키되 바로 시작 */
fun syncNow(ctx: Context) {
    val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints(Settings(ctx))).build()
    WorkManager.getInstance(ctx).enqueueUniqueWork("now", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
}
