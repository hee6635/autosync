package com.hee.autosync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns

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

/** 보내는 폰: 자동 폴더 + 수동으로 고른 것 → 드라이브 → MD5 일치하면 폰에서 삭제 */
fun upload(ctx: Context, s: Settings, drive: Drive, folder: String) {
    val cr = ctx.contentResolver
    val fresh = System.currentTimeMillis() - 60_000 // 방금 찍어서 아직 쓰는 중일 수 있는 파일은 다음에
    val pending = s.pending
    var sent = 0
    val skipped = mutableMapOf<String, Int>()

    for (root in s.autoTrees + pending) {
        val rootUri = Uri.parse(root)
        val docs = try { listDocs(ctx, rootUri) } catch (e: Exception) {
            log(ctx, "폴더 읽기 실패(권한 확인): ${e.message}"); continue
        }
        for (d in docs) {
            val why = skipReason(d.name, d.size)
            if (why != null) { skipped[why] = (skipped[why] ?: 0) + 1; continue }
            if (root !in pending && d.modified > fresh) continue
            val key = "${d.uri}|${d.size}|${d.modified}"
            if (key in s.uploaded) continue

            val local = md5(cr.openInputStream(d.uri) ?: continue)
            val r = drive.upload(folder, d.name, d.size, d.modified, d.mime.ifEmpty { "application/octet-stream" },
                cr.openInputStream(d.uri) ?: continue)
            if (r.md5 != local) {
                log(ctx, "MD5 불일치, 폰 파일 보관: ${d.name}")
                drive.delete(r.id)
                continue
            }
            if (s.deleteAfter) {
                val deleted = try { DocumentsContract.deleteDocument(cr, d.uri) } catch (e: Exception) { false }
                if (!deleted) log(ctx, "업로드됨, 폰 삭제 실패(쓰기 권한 없음): ${d.name}")
            } else s.uploaded = s.uploaded + key
            sent++
            log(ctx, "보냄 ${d.name}")
        }
        if (root in pending) {
            s.pending = s.pending - root
            try { cr.releasePersistableUriPermission(rootUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            catch (_: SecurityException) {}
        }
    }
    if (sent > 0 || pending.isNotEmpty())
        log(ctx, "보내기 완료 ${sent}개" + if (skipped.isEmpty()) "" else ", 건너뜀 " + skipped.entries.joinToString { "${it.key} ${it.value}" })
}
