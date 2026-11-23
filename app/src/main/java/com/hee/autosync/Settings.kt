package com.hee.autosync

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun bool(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun setBool(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()
    private fun int(k: String, d: Int) = p.getInt(k, d)
    private fun setInt(k: String, v: Int) = p.edit().putInt(k, v).apply()

    var receiver: Boolean get() = bool("receiver", false); set(v) = setBool("receiver", v)
    /** 15분마다 자동 실행. 끄면 버튼으로만. */
    var auto: Boolean get() = bool("auto", true); set(v) = setBool("auto", v)
    var wifiOnly: Boolean get() = bool("wifiOnly", true); set(v) = setBool("wifiOnly", v)
    var chargingOnly: Boolean get() = bool("chargingOnly", false); set(v) = setBool("chargingOnly", v)
    /** 처음 시작 질문에서 정함. 답하기 전엔 보내지 않음. */
    var deleteAfter: Boolean get() = bool("deleteAfter2", false); set(v) = setBool("deleteAfter2", v)
    var askedDelete: Boolean get() = bool("askedDelete", false); set(v) = setBool("askedDelete", v)
    var concurrency: Int get() = int("concurrency", 3); set(v) = setInt("concurrency", v.coerceIn(1, 5))
    var minFreeGb: Int get() = int("minFreeGb", 10); set(v) = setInt("minFreeGb", v)
    /** 예약 시간대, 하루 중 분(0~1439). 시작 -1 = 예약 없음(언제나). */
    var upStart: Int get() = int("upStart", -1); set(v) = setInt("upStart", v)
    var upEnd: Int get() = int("upEnd", -1); set(v) = setInt("upEnd", v)
    var downStart: Int get() = int("downStart", -1); set(v) = setInt("downStart", v)
    var downEnd: Int get() = int("downEnd", -1); set(v) = setInt("downEnd", v)

    /** 자동으로 보낼 폴더(SAF 트리 URI): DCIM/Camera, SD카드 등 */
    var autoTrees: Set<String>
        get() = p.getStringSet("autoTrees", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("autoTrees", v).apply()
    /** 수동으로 고른 파일/폴더, 한 번 보내고 지워짐 */
    var pending: Set<String>
        get() = p.getStringSet("pending", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("pending", v).apply()
    // shortcut: "보낸 후 삭제"를 끄면 보낸 파일 목록이 계속 늘어남, 수만 개가 되면 DB로 바꿀 것
    var uploaded: Set<String>
        get() = p.getStringSet("uploaded", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("uploaded", v).apply()
    /** 받는 폰: 같은 와이파이에서 직접 받기 */
    var direct: Boolean get() = bool("direct", true); set(v) = setBool("direct", v)
    var directKey: String?
        get() = p.getString("directKey", null)
        set(v) = p.edit().putString("directKey", v).apply()
    var lastPeer: String
        get() = p.getString("lastPeer", "")!!
        set(v) = p.edit().putString("lastPeer", v).apply()
    /** 보낸 뒤 폰에서 못 지운 공유 파일 "uri\t크기\t이름" → 화면에서 삭제 확인창으로 지움 */
    var toDelete: Set<String>
        get() = p.getStringSet("toDelete", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("toDelete", v).apply()
    /** 보내는 폰: 같은 와이파이에 받는 폰이 있으면 바로 보내기 */
    var directSend: Boolean get() = bool("directSend", true); set(v) = setBool("directSend", v)
    /** 받는 폰: 추가로 받을 드라이브 폴더 */
    var recvFolders: List<RecvFolder>
        get() = JSONArray(p.getString("recvFolders", "[]")).let { a ->
            (0 until a.length()).map { a.getJSONObject(it).let { o -> RecvFolder(o.getString("id"), o.getString("name"), o.getBoolean("del")) } }
        }
        set(v) = p.edit().putString("recvFolders", JSONArray(v.map { JSONObject().put("id", it.id).put("name", it.name).put("del", it.del) }).toString()).apply()
    // shortcut: "남기기" 폴더에서 받은 파일 id가 계속 쌓임, 수만 개가 되면 DB로 바꿀 것
    var seen: Set<String>
        get() = p.getStringSet("seen", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("seen", v).apply()
    var folderId: String?
        get() = p.getString("folderId", null)
        set(v) = p.edit().putString("folderId", v).apply()

    /** 폰 폴더 → 드라이브 PixelSync 안 하위 폴더 이름 */
    fun sub(uri: String): String = JSONObject(p.getString("subs", "{}")!!).optString(uri, defaultSub())
    fun setSub(uri: String, name: String) {
        val o = JSONObject(p.getString("subs", "{}")!!).put(uri, name)
        p.edit().putString("subs", o.toString()).apply()
    }
    fun defaultSub(): String = p.getString("defaultSub", null) ?: Build.MODEL.ifBlank { "폰" }

    fun inUploadWindow(now: Int) = inWindow(now, upStart, upEnd)
    fun inDownloadWindow(now: Int) = inWindow(now, downStart, downEnd)
}

class RecvFolder(val id: String, val name: String, val del: Boolean)

/** 예약 시간대 안인가. 자정을 넘는 시간대(22:00~06:00)도 됨. */
fun inWindow(now: Int, start: Int, end: Int): Boolean = when {
    start < 0 || start == end -> true
    start < end -> now in start until end
    else -> now >= start || now < end
}

private val logLock = Any()

fun log(ctx: Context, msg: String) = synchronized(logLock) {
    val f = File(ctx.filesDir, "log.txt")
    val time = SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(Date())
    val lines = (if (f.exists()) f.readLines() else emptyList()) + "$time $msg"
    f.writeText(lines.takeLast(300).joinToString("\n"))
}

fun readLog(ctx: Context): String =
    File(ctx.filesDir, "log.txt").takeIf { it.exists() }?.readLines()?.reversed()?.joinToString("\n") ?: ""
