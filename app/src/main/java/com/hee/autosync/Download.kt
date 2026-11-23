package com.hee.autosync

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val GB = 1024L * 1024 * 1024

/** 드라이브에서 온 이름을 DCIM/Camera 안의 안전한 파일 이름으로 (경로 이동·숨김 파일 방지) */
fun safeName(name: String): String {
    val n = name.replace('/', '_').replace('\\', '_').trim()
    return if (n.isEmpty() || n.startsWith(".")) "_$n" else n
}

/** 같은 이름이 있으면 photo_1.jpg, photo_2.jpg ... (taken: 동시에 받는 중인 이름) */
fun uniqueFile(dir: File, name: String, taken: Set<String> = emptySet()): File {
    val base = name.substringBeforeLast('.')
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var i = 1
    var f = File(dir, name)
    while (f.exists() || f.name in taken) f = File(dir, "${base}_${i++}$ext")
    return f
}

fun cameraDir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")

/** 드라이브 PixelSync 와 그 하위 폴더의 파일 전부 (하위 폴더 이름, 파일) */
fun remoteJobs(drive: Drive, folder: String): List<Pair<String, Remote>> =
    drive.list(folder).map { "" to it } + drive.subfolders(folder).flatMap { (id, name) -> drive.list(id).map { name to it } }

/** 받는 폰(픽셀1): 드라이브 → DCIM/Camera (여러 개 동시) → MD5 확인 → 드라이브에서 삭제. 구글포토가 DCIM/Camera를 백업함. */
suspend fun download(ctx: Context, s: Settings, drive: Drive, folder: String) {
    val dir = cameraDir()
    if (!dir.isDirectory && !dir.mkdirs()) { log(ctx, "DCIM/Camera 폴더를 만들 수 없음(저장소 권한 확인)"); return }
    val jobs = remoteJobs(drive, folder)
    if (jobs.isEmpty()) return
    val minFree = s.minFreeGb * GB
    val taken = mutableSetOf<String>()
    val full = AtomicBoolean(false)
    val got = AtomicInteger(0)
    Status.start(jobs.size, jobs.sumOf { it.second.size })
    val gate = Semaphore(s.concurrency)
    try {
        coroutineScope {
            for ((sub, r) in jobs) launch {
                gate.withPermit {
                    val label = if (sub.isEmpty()) r.name else "$sub / ${r.name}"
                    try {
                        if (full.get() || StatFs(dir.path).availableBytes - r.size < minFree) {
                            if (!full.getAndSet(true)) log(ctx, "여유 공간 ${s.minFreeGb}GB 미만, 받기 중지")
                            return@withPermit
                        }
                        val name = safeName(r.name)
                        val existing = File(dir, name)
                        // 전에 받고 드라이브 삭제 전에 끊긴 경우: 같은 파일이면 드라이브 쪽만 지움
                        if (existing.exists() && existing.length() == r.size && md5(existing.inputStream()) == r.md5) {
                            drive.delete(r.id); Stats.add(ctx, "delCloud", r.name, r.size); return@withPermit
                        }
                        val target = synchronized(taken) { uniqueFile(dir, name, taken).also { taken += it.name } }
                        val tmp = File(dir, ".${target.name}.part")
                        Status.items[label] = 0
                        try {
                            val md = MessageDigest.getInstance("MD5")
                            DigestOutputStream(tmp.outputStream(), md).use { out ->
                                drive.download(r.id, out) { n -> Status.items[label] = if (r.size > 0) (n * 100 / r.size).toInt() else 0 }
                            }
                            if (hex(md.digest()) != r.md5) { log(ctx, "MD5 불일치, 드라이브에 보관: ${r.name}"); return@withPermit }
                            if (r.modified > 0) tmp.setLastModified(r.modified) // 영상은 구글포토가 수정 시각으로 날짜를 잡음
                            if (!tmp.renameTo(target)) { log(ctx, "저장 실패: ${r.name}"); return@withPermit }
                        } finally {
                            tmp.delete()
                            synchronized(taken) { taken -= target.name }
                        }
                        MediaScannerConnection.scanFile(ctx, arrayOf(target.path), null, null)
                        Stats.add(ctx, "down", target.name, r.size)
                        drive.delete(r.id)
                        Stats.add(ctx, "delCloud", r.name, r.size)
                        got.incrementAndGet()
                    } catch (e: Exception) {
                        log(ctx, "실패 $label: ${e.message}")
                    } finally {
                        Status.items.remove(label)
                        Status.done(r.size)
                    }
                }
            }
        }
    } finally {
        Status.stop()
    }
    log(ctx, "받기 ${got.get()}/${jobs.size}개")
}
