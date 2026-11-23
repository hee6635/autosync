package com.hee.autosync

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant

data class Remote(val id: String, val name: String, val md5: String, val size: Long, val modified: Long)

/** 구글드라이브 REST v3. 토큰은 호출마다 새로 받음(긴 업로드 중 1시간 만료 대비). */
class Drive(private val token: () -> String) {
    companion object {
        const val FOLDER = "PixelSync"
        private const val API = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private const val FIELDS = "id,name,md5Checksum,size,modifiedTime"
        private const val DIR = "application/vnd.google-apps.folder"

        fun request(): AuthorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive")))
            .build()

        /** 이미 허용된 경우 화면 없이 토큰을 줌. null이면 앱에서 로그인 필요. 메인 스레드에서 부르면 안 됨. */
        fun token(ctx: Context): String? {
            val r = Tasks.await(Identity.getAuthorizationClient(ctx).authorize(request()))
            return if (r.hasResolution()) null else r.accessToken
        }
    }

    private fun open(url: String, method: String = "GET"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer ${token()}")
        }

    private fun HttpURLConnection.ok(): HttpURLConnection {
        if (responseCode !in 200..299) {
            val body = errorStream?.bufferedReader()?.readText().orEmpty()
            disconnect()
            throw IOException("Drive $requestMethod $responseCode: ${body.take(300)}")
        }
        return this
    }

    private fun HttpURLConnection.json() = JSONObject(ok().inputStream.bufferedReader().use { it.readText() })

    private fun HttpURLConnection.sendJson(o: JSONObject) {
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        outputStream.use { it.write(o.toString().toByteArray()) }
    }

    private fun remote(o: JSONObject) = Remote(
        o.getString("id"), o.getString("name"), o.optString("md5Checksum"),
        o.optString("size", "0").toLong(),
        o.optString("modifiedTime").let { if (it.isEmpty()) 0 else Instant.parse(it).toEpochMilli() },
    )

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    /** parent 안의 폴더 id, 없으면 만듦. 기본은 내 드라이브 루트의 PixelSync. */
    fun folderId(name: String = FOLDER, parent: String = "root"): String {
        val query = "name='${esc(name)}' and mimeType='$DIR' and '$parent' in parents and trashed=false"
        val found = open("$API?q=${q(query)}&fields=files(id)").json().getJSONArray("files")
        if (found.length() > 0) return found.getJSONObject(0).getString("id")
        return open("$API?fields=id", "POST").apply {
            sendJson(JSONObject().put("name", name).put("mimeType", DIR).put("parents", JSONArray().put(parent)))
        }.json().getString("id")
    }

    /** parent 바로 아래 폴더들 (id, 이름) */
    fun subfolders(parent: String): List<Pair<String, String>> {
        val query = "'$parent' in parents and mimeType='$DIR' and trashed=false"
        val a = open("$API?q=${q(query)}&pageSize=200&fields=files(id,name)").json().getJSONArray("files")
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("id") to o.getString("name") } }
    }

    /** 구글 계정 용량 (전체, 사용). 전체가 null이면 무제한. */
    fun quota(): Pair<Long?, Long> {
        val q = open("https://www.googleapis.com/drive/v3/about?fields=storageQuota").json().getJSONObject("storageQuota")
        return q.optString("limit").toLongOrNull() to q.optString("usage", "0").toLong()
    }

    fun list(folder: String): List<Remote> {
        val out = mutableListOf<Remote>()
        var page: String? = null
        do {
            val query = "'$folder' in parents and trashed=false and mimeType!='$DIR'"
            val url = "$API?q=${q(query)}&pageSize=200&orderBy=createdTime&fields=nextPageToken,files($FIELDS)" +
                (page?.let { "&pageToken=${q(it)}" } ?: "")
            val o = open(url).json()
            val files = o.getJSONArray("files")
            for (i in 0 until files.length()) out += remote(files.getJSONObject(i))
            page = o.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return out
    }

    /** 이어받기 방식 업로드. 드라이브는 업로드가 끝나야 파일이 보이므로 반쯤 올라간 파일을 받는 쪽이 볼 일은 없음. */
    fun upload(folder: String, name: String, size: Long, modified: Long, mime: String, data: InputStream, progress: (Long) -> Unit = {}): Remote {
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(folder))
            .put("modifiedTime", Instant.ofEpochMilli(modified).toString())
        val start = open("$UPLOAD?uploadType=resumable&fields=$FIELDS", "POST").apply {
            setRequestProperty("X-Upload-Content-Type", mime)
            setRequestProperty("X-Upload-Content-Length", size.toString())
            sendJson(meta)
        }.ok()
        val location = start.getHeaderField("Location") ?: throw IOException("업로드 주소 없음")
        start.disconnect()
        // shortcut: 끊기면 처음부터 다시 올림(다음 실행 때), 아주 큰 영상이 자주 끊기면 이어올리기 구현
        val put = (URL(location).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 300_000
            setFixedLengthStreamingMode(size)
            setRequestProperty("Content-Type", mime)
        }
        data.use { input -> put.outputStream.use { copy(input, it, progress) } }
        return remote(put.json())
    }

    fun download(id: String, out: OutputStream, progress: (Long) -> Unit = {}) {
        val c = open("$API/$id?alt=media").ok()
        c.inputStream.use { copy(it, out, progress) }
    }

    fun delete(id: String) {
        open("$API/$id", "DELETE").ok().disconnect()
    }
}

/** 복사하면서 지금까지 옮긴 바이트를 알려줌 */
fun copy(input: InputStream, out: OutputStream, progress: (Long) -> Unit) {
    val buf = ByteArray(1 shl 16)
    var done = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        done += n
        progress(done)
    }
}

fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

fun md5(input: InputStream): String {
    val d = MessageDigest.getInstance("MD5")
    val buf = ByteArray(1 shl 16)
    input.use { while (true) { val n = it.read(buf); if (n < 0) break; d.update(buf, 0, n) } }
    return hex(d.digest())
}
