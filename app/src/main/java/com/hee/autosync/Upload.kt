package com.hee.autosync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class Doc(val uri: Uri, val name: String, val size: Long, val modified: Long, val mime: String)

private val IMAGE = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "dng", "bmp")
private val VIDEO = setOf("mp4", "mov", "3gp", "m4v", "mkv", "webm", "avi", "mpg", "mpeg", "wmv")
private const val MB = 1024L * 1024

/** 구글포토가 받는 형식/크기만. null = 보냄, 아니면 건너뛰는 이유. */
fun skipReason(name: String, size: Long): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        name.startsWith(".") -> "숨김/임시 파일"
        size <= 0 -> "크기 0"
        ext in IMAGE -> if (size > 200 * MB) "사진 200MB 초과" else null
        ext in VIDEO -> if (size > 10 * 1024 * MB) "영상 10GB 초과" else null
        else -> "형식 미지원"
    }
}

/** 트리(폴더)면 하위 폴더까지 전부, 파일이면 그 파일 하나 */
fun listDocs(ctx: Context, uri: Uri): List<Doc> {
    val cr = ctx.contentResolver
    val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED, Document.COLUMN_MIME_TYPE)
    if (!DocumentsContract.isTreeUri(uri)) {
        cr.query(uri, cols, null, null, null)?.use { c ->
            if (c.moveToFirst()) return listOf(Doc(uri, c.getString(1), c.getLong(2), c.getLong(3), c.getString(4) ?: ""))
        }
        // 일부 앱(갤러리 등)이 주는 URI는 문서 열이 없어서 이름/크기만 읽음
        cr.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) return listOf(Doc(uri, c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)),
                c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE)), System.currentTimeMillis(), cr.getType(uri) ?: ""))
        }
        return emptyList()
    }
    val out = mutableListOf<Doc>()
    val dirs = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(uri)))
    while (dirs.isNotEmpty()) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, dirs.removeFirst())
        cr.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val mime = c.getString(4) ?: ""
                if (mime == Document.MIME_TYPE_DIR) dirs += id
                else out += Doc(DocumentsContract.buildDocumentUriUsingTree(uri, id), c.getString(1), c.getLong(2), c.getLong(3), mime)
            }
        }
    }
    return out
}

/** 보낼 파일 하나와 드라이브 하위 폴더 */
class Job(val doc: Doc, val sub: String, val root: String)

/** 지금 보낼 것 목록(오래된 것부터). 화면의 "N개 · X GB 대기"에도 씀. */
fun pendingJobs(ctx: Context, s: Settings, manual: Boolean, skipped: MutableMap<String, Int> = mutableMapOf(), found: (Int) -> Unit = {}): List<Job> {
    // 방금 찍어서 아직 쓰는 중일 수 있는 파일은 다음에. 버튼으로 보낼 땐 사람이 찍고 누른 거라 5초만 기다림.
    val fresh = System.currentTimeMillis() - if (manual) 5_000 else 60_000
    val pending = s.pending
    val uploaded = s.uploaded
    val jobs = mutableListOf<Job>()
    for (root in s.autoTrees + pending) {
        val docs = try { listDocs(ctx, Uri.parse(root)) } catch (e: Exception) {
            log(ctx, "폴더 읽기 실패(권한 확인): ${e.message}"); continue
        }
        for (d in docs) {
            val why = skipReason(d.name, d.size)
            if (why != null) { skipped[why] = (skipped[why] ?: 0) + 1; continue }
            if (root !in pending && d.modified > fresh) continue
            if (key(d) in uploaded) continue
            jobs += Job(d, s.sub(root), root)
            if (jobs.size % 50 == 0) found(jobs.size)
        }
        found(jobs.size)
    }
    return jobs.distinctBy { it.doc.uri }.sortedBy { it.doc.modified }
}

private fun key(d: Doc) = "${d.uri}|${d.size}|${d.modified}"

private const val QUOTA_MARGIN = 200L * 1024 * 1024

/** 보내는 폰: 폴더 → 드라이브 PixelSync/<하위 폴더>. 여러 개 동시에, 올리면서 MD5 계산 → 일치하면 폰에서 삭제 */
suspend fun upload(ctx: Context, s: Settings, drive: Drive, folder: String, manual: Boolean) {
    val cr = ctx.contentResolver
    val skipped = mutableMapOf<String, Int>()
    Status.stage(2, "폴더 확인 중")
    val jobs = pendingJobs(ctx, s, manual, skipped) { n -> Status.note = "폴더 확인 중 · ${"%,d".format(n)}개 찾음" }
    val pending = s.pending
    if (jobs.isEmpty()) { clearPending(ctx, s, pending, emptySet()); Status.note = "보낼 파일 없음"; return }

    Status.stage(3, "용량 확인 중")
    val (limit, usage) = drive.quota()
    val free = AtomicLong(if (limit == null) Long.MAX_VALUE else limit - usage)
    val subIds = ConcurrentHashMap<String, String>()
    val failedRoots = ConcurrentHashMap.newKeySet<String>()
    val full = AtomicBoolean(false)
    val sent = AtomicInteger(0)
    val deleteAfter = s.deleteAfter
    val lock = Any()

    // 같은 와이파이에 받는 폰이 있으면 묻지 않고 직접, 없으면 클라우드
    val peer = if (Direct.onWifi(ctx)) try { Direct.discover(ctx) } catch (e: Exception) { null } else null
    val dkey = peer?.let { try { Direct.key(s, drive, folder) } catch (e: Exception) { null } }
    val direct = AtomicBoolean(peer != null && dkey != null)
    val batch = JSONObject().put("id", UUID.randomUUID().toString()).put("count", jobs.size).put("bytes", jobs.sumOf { it.doc.size })
    val from = s.defaultSub()

    Status.start(jobs.size, jobs.sumOf { it.doc.size })
    Status.route = if (direct.get()) "direct:${peer!!.name}" else "cloud"
    Status.stage(4, "")
    val gate = Semaphore(s.concurrency)
    try {
        coroutineScope {
            for (j in jobs) launch {
                gate.withPermit {
                    val d = j.doc
                    if (Status.cancelled) { failedRoots += j.root; return@withPermit }
                    if (!direct.get() && (full.get() || free.get() - d.size < QUOTA_MARGIN)) {
                        if (!full.getAndSet(true)) log(ctx, "구글 계정 용량 부족, 업로드 멈춤")
                        failedRoots += j.root; return@withPermit
                    }
                    Status.begin(d.name, d.size)
                    try {
                        var viaDirect = false
                        if (direct.get()) try {
                            val input = cr.openInputStream(d.uri) ?: throw java.io.IOException("파일을 열 수 없음")
                            viaDirect = Direct.send(peer!!, dkey!!, from, d, batch, input) { n -> Status.progress(d.name, n) }
                            if (!viaDirect) throw java.io.IOException("MD5 불일치")
                        } catch (e: java.io.IOException) {
                            if (e is CancelledByUser) throw e
                            if (direct.getAndSet(false)) {
                                log(ctx, "직접 전송 끊김, 클라우드로: ${e.message}")
                                Status.route = "cloud"
                            }
                            Status.begin(d.name, d.size)
                        }
                        if (!viaDirect) {
                        val sub = synchronized(subIds) { subIds.getOrPut(j.sub) { drive.folderId(j.sub, folder) } } // 같은 폴더를 두 번 만들지 않게
                        val md = MessageDigest.getInstance("MD5")
                        val input = cr.openInputStream(d.uri) ?: throw java.io.IOException("파일을 열 수 없음")
                        val r = drive.upload(sub, d.name, d.size, d.modified, d.mime.ifEmpty { "application/octet-stream" },
                            DigestInputStream(input, md)) { n -> Status.progress(d.name, n) }
                        if (r.md5 != hex(md.digest())) {
                            drive.delete(r.id)
                            throw java.io.IOException("MD5 불일치, 폰 파일 보관")
                        }
                        free.addAndGet(-d.size)
                        }
                        Stats.add(ctx, "up", d.name, d.size)
                        sent.incrementAndGet()
                        if (deleteAfter) {
                            val deleted = try { DocumentsContract.deleteDocument(cr, d.uri) } catch (e: Exception) { false }
                            if (deleted) Stats.add(ctx, "delLocal", d.name, d.size)
                            else log(ctx, "업로드됨, 폰 삭제 실패(쓰기 권한 없음): ${d.name}")
                        } else synchronized(lock) { s.uploaded = s.uploaded + key(d) }
                    } catch (e: Exception) {
                        failedRoots += j.root
                        if (e !is CancelledByUser) log(ctx, "실패 ${d.name}: ${e.message}")
                    } finally {
                        Status.done(d.name, d.size)
                    }
                }
            }
        }
    } finally {
        Status.stop()
    }
    clearPending(ctx, s, pending, failedRoots)
    Status.note = if (Status.cancelled) "취소됨" else "완료 · ${sent.get()}개 보냄"
    log(ctx, (if (Status.cancelled) "취소됨, " else "") + "보내기 ${sent.get()}/${jobs.size}개" + if (skipped.isEmpty()) "" else ", 건너뜀 " + skipped.entries.joinToString { "${it.key} ${it.value}" })
}

/** 수동으로 고른 것 중 다 보낸 것만 목록에서 빼고 권한 반납 */
private fun clearPending(ctx: Context, s: Settings, pending: Set<String>, failed: Set<String>) {
    val done = pending - failed
    if (done.isEmpty()) return
    s.pending = s.pending - done
    for (root in done) try {
        ctx.contentResolver.releasePersistableUriPermission(Uri.parse(root),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    } catch (_: SecurityException) {}
}
