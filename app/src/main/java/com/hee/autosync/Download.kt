package com.hee.autosync

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.StatFs
import java.io.File

private const val GB = 1024L * 1024 * 1024

/** 드라이브에서 온 이름을 DCIM/Camera 안의 안전한 파일 이름으로 (경로 이동·숨김 파일 방지) */
fun safeName(name: String): String {
    val n = name.replace('/', '_').replace('\\', '_').trim()
    return if (n.isEmpty() || n.startsWith(".")) "_$n" else n
}

/** 같은 이름이 있으면 photo_1.jpg, photo_2.jpg ... */
fun uniqueFile(dir: File, name: String): File {
    val base = name.substringBeforeLast('.')
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var i = 1
    var f = File(dir, name)
    while (f.exists()) f = File(dir, "${base}_${i++}$ext")
    return f
}

/** 받는 폰(픽셀1): 드라이브 → DCIM/Camera → MD5 확인 → 드라이브에서 삭제. 구글포토가 DCIM/Camera를 백업함. */
fun download(ctx: Context, s: Settings, drive: Drive, folder: String) {
    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
    if (!dir.isDirectory && !dir.mkdirs()) { log(ctx, "DCIM/Camera 폴더를 만들 수 없음(저장소 권한 확인)"); return }
    var got = 0
    for (r in drive.list(folder)) {
        if (StatFs(dir.path).availableBytes - r.size < s.minFreeGb * GB) {
            log(ctx, "여유 공간 ${s.minFreeGb}GB 미만, 받기 중지"); break
        }
        val name = safeName(r.name)
        val existing = File(dir, name)
        // 전에 받고 드라이브 삭제 전에 끊긴 경우: 같은 파일이면 드라이브 쪽만 지움
        if (existing.exists() && existing.length() == r.size && md5(existing.inputStream()) == r.md5) {
            drive.delete(r.id); continue
        }
        val target = uniqueFile(dir, name)
        val tmp = File(dir, ".${target.name}.part")
        try {
            tmp.outputStream().use { drive.download(r.id, it) }
            if (md5(tmp.inputStream()) != r.md5) { log(ctx, "MD5 불일치, 드라이브에 보관: ${r.name}"); continue }
            if (r.modified > 0) tmp.setLastModified(r.modified) // 영상은 구글포토가 수정 시각으로 날짜를 잡음
            if (!tmp.renameTo(target)) { log(ctx, "저장 실패: ${r.name}"); continue }
        } finally {
            tmp.delete()
        }
        MediaScannerConnection.scanFile(ctx, arrayOf(target.path), null, null)
        drive.delete(r.id)
        got++
        log(ctx, "받음 ${target.name}")
    }
    if (got > 0) log(ctx, "받기 완료 ${got}개")
}
