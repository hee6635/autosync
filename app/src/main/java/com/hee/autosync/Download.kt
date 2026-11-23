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

/** 받을 파일 하나: 어느 폴더에서 왔는지(화면 표시용), 받은 뒤 드라이브에서 지울지 */
class RJob(val sub: String, val r: Remote, val del: Boolean)

/** 폴더와 그 아래 모든 폴더의 파일 (깊이 10까지) */
private fun walk(drive: Drive, id: String, label: String, depth: Int = 0): List<Pair<String, Remote>> =
    drive.list(id).map { label to it } +
        if (depth >= 10) emptyList() else drive.subfolders(id).flatMap { (cid, n) -> walk(drive, cid, "$label/$n", depth + 1) }

/** PixelSync(받으면 지움) + 사용자가 추가한 드라이브 폴더(폴더마다 지울지 정함, 사진·영상만) */
fun remoteJobs(drive: Drive, folder: String, s: Settings): List<RJob> {
    val out = drive.list(folder).map { RJob("", it, true) } +
        drive.subfolders(folder).flatMap { (id, name) -> drive.list(id).map { RJob(name, it, true) } }
    val seen = s.seen
    val extra = s.recvFolders.flatMap { f ->
        walk(drive, f.id, f.name).filter { (_, r) -> skipReason(r.name, r.size) == null && (f.del || r.id !in seen) }
            .map { (label, r) -> RJob(label, r, f.del) }
    }
    return (out + extra).distinctBy { it.r.id }
}

/** 받고 나서: 지우는 폴더면 드라이브에서 지우고, 남기는 폴더면 다시 안 받게 기록 */
private fun afterGot(ctx: Context, s: Settings, drive: Drive, j: RJob) {
    if (j.del) { drive.delete(j.r.id); Stats.add(ctx, "delCloud", j.r.name, j.r.size) }
    else synchronized(s) { s.seen = s.seen + j.r.id }
}

/** 받는 폰(픽셀1): 드라이브 → DCIM/Camera (여러 개 동시) → MD5 확인 → 드라이브에서 삭제. 구글포토가 DCIM/Camera를 백업함. */
suspend fun download(ctx: Context, s: Settings, drive: Drive, folder: String) {
    val dir = cameraDir()
    if (!dir.isDirectory && !dir.mkdirs()) { log(ctx, "DCIM/Camera 폴더를 만들 수 없음(저장소 권한 확인)"); return }
    Status.stage(2, "드라이브 목록 확인 중")
    val jobs = remoteJobs(drive, folder, s)
    if (jobs.isEmpty()) { Status.note = "받을 파일 없음"; return }
    Status.stage(3, "공간 확인 중")
    val minFree = s.minFreeGb * GB
    val full = AtomicBoolean(false)
    val got = AtomicInteger(0)
    Status.start(jobs.size, jobs.sumOf { it.r.size })
    Status.stage(4, "")
    val gate = Semaphore(s.concurrency)
    try {
        coroutineScope {
            for (j in jobs) launch {
                gate.withPermit {
                    val sub = j.sub; val r = j.r
                    val label = if (sub.isEmpty()) r.name else "$sub / ${r.name}"
                    if (Status.cancelled) return@withPermit
                    try {
                        if (full.get() || StatFs(dir.path).availableBytes - r.size < minFree) {
                            if (!full.getAndSet(true)) log(ctx, "여유 공간 ${s.minFreeGb}GB 미만, 받기 중지")
                            return@withPermit
                        }
                        val name = safeName(r.name)
                        val existing = File(dir, name)
                        // 전에 받고 드라이브 삭제 전에 끊긴 경우: 같은 파일이면 드라이브 쪽만 지움
                        if (existing.exists() && existing.length() == r.size && md5(existing.inputStream()) == r.md5) {
                            afterGot(ctx, s, drive, j); return@withPermit
                        }
                        val target = synchronized(receiving) { uniqueFile(dir, name, receiving).also { receiving += it.name } }
                        val tmp = File(dir, ".${target.name}.part")
                        Status.begin(label, r.size)
                        try {
                            val md = MessageDigest.getInstance("MD5")
                            DigestOutputStream(tmp.outputStream(), md).use { out ->
                                drive.download(r.id, out) { n -> Status.progress(label, n) }
                            }
                            if (hex(md.digest()) != r.md5) { log(ctx, "MD5 불일치, 드라이브에 보관: ${r.name}"); return@withPermit }
                            if (r.modified > 0) tmp.setLastModified(r.modified) // 영상은 구글포토가 수정 시각으로 날짜를 잡음
                            if (!tmp.renameTo(target)) { log(ctx, "저장 실패: ${r.name}"); return@withPermit }
                        } finally {
                            tmp.delete()
                            synchronized(receiving) { receiving -= target.name }
                        }
                        MediaScannerConnection.scanFile(ctx, arrayOf(target.path), null, null)
                        Stats.add(ctx, "down", target.name, r.size)
                        afterGot(ctx, s, drive, j)
                        got.incrementAndGet()
                    } catch (e: Exception) {
                        if (e !is CancelledByUser) log(ctx, "실패 $label: ${e.message}")
                    } finally {
                        Status.done(label, r.size)
                    }
                }
            }
        }
    } finally {
        Status.stop()
    }
    Status.note = if (Status.cancelled) "취소됨" else "완료 · ${got.get()}개 받음"
    log(ctx, (if (Status.cancelled) "취소됨, " else "") + "받기 ${got.get()}/${jobs.size}개")
}
