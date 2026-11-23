package com.hee.autosync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/** 지금 진행 상황. 작업(Worker)과 화면이 같은 프로세스라 메모리로 주고받음. */
object Status {
    @Volatile var running = false
    @Volatile var totalBytes = 0L
    @Volatile var remainCount = 0
    @Volatile var remainBytes = 0L
    @Volatile var note = ""
    /** 작업 중(준비 단계 포함) · 단계 0 로그인 1 접속 2 폴더/목록 3 용량/공간 4 옮기는 중 · 취소 요청 */
    @Volatile var busy = false
    @Volatile var step = -1
    @Volatile var cancelled = false
    /** 지금 가는 길: "direct:<상대 폰>" · "cloud" · "" */
    @Volatile var route = ""
    /** 받는 폰: 와이파이 직접 받기 창구가 열려 있음 */
    @Volatile var listening = false
    /** 지금 옮기는 파일 이름 → [옮긴 바이트, 전체 바이트]. 다 옮기면 "확인 중"(드라이브 응답·MD5 비교). */
    val items = ConcurrentHashMap<String, LongArray>()

    fun begin(name: String, size: Long) { items[name] = longArrayOf(0, size) }
    fun progress(name: String, done: Long) { check(); items[name]?.set(0, done) }

    @Synchronized fun start(count: Int, bytes: Long) {
        running = true; totalBytes = bytes; remainCount = count; remainBytes = bytes; items.clear()
    }
    @Synchronized fun done(name: String, bytes: Long) { items.remove(name); remainCount--; remainBytes -= bytes }
    fun stop() { running = false; items.clear(); route = "" }
    fun stage(n: Int, text: String) { step = n; note = text }

    /** 옮기는 중 진행 알림에서 부름: 취소 눌렀으면 그 자리에서 끊음 */
    fun check() { if (cancelled) throw CancelledByUser() }

    fun json(): JSONObject = JSONObject()
        .put("running", running).put("note", note).put("busy", busy).put("step", step).put("cancelled", cancelled).put("route", route).put("listening", listening)
        .put("totalBytes", totalBytes).put("remainCount", remainCount).put("remainBytes", remainBytes)
        .put("items", JSONArray(items.entries.sortedBy { it.key }.map { (n, v) -> JSONObject().put("name", n).put("done", v[0]).put("size", v[1]) }))
        // 원 %: 시작할 때 계산한 전체 중 지금까지 올린 바이트 (옮기는 중인 파일 진행분 포함)
        .put("sentBytes", synchronized(this) { totalBytes - remainBytes + items.values.sumOf { it[0] } })
}

class CancelledByUser : java.io.IOException("취소됨")

/** 최근 변경사항: up(업로드) · delLocal(장치에서 삭제) · down(다운로드) · delCloud(클라우드에서 삭제) */
object Stats {
    val TYPES = listOf("up", "delLocal", "down", "delCloud")
    private const val KEEP = 5000

    private fun file(ctx: Context) = File(ctx.filesDir, "events.tsv")

    @Synchronized fun add(ctx: Context, type: String, name: String, size: Long) {
        val f = file(ctx)
        f.appendText("${System.currentTimeMillis()}\t$type\t$size\t${name.replace('\t', ' ').replace('\n', ' ')}\n")
        val p = ctx.getSharedPreferences("stats", Context.MODE_PRIVATE)
        p.edit().putInt("n_$type", p.getInt("n_$type", 0) + 1).putLong("b_$type", p.getLong("b_$type", 0) + size).apply()
        if (f.length() > KEEP * 120L) f.writeText(f.readLines().takeLast(KEEP).joinToString("\n", postfix = "\n"))
    }

    private fun startOfToday() = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun events(ctx: Context): List<List<String>> = synchronized(this) {
        val f = file(ctx)
        if (!f.exists()) emptyList() else f.readLines().mapNotNull { l -> l.split('\t', limit = 4).takeIf { it.size == 4 } }
    }

    /** {today:{up:{n,b},...}, total:{...}} — 전체는 지운 기록과 상관없이 누적값 */
    fun summary(ctx: Context): JSONObject {
        val p = ctx.getSharedPreferences("stats", Context.MODE_PRIVATE)
        val today = JSONObject(); val total = JSONObject()
        val since = startOfToday()
        val todayEv = events(ctx).filter { it[0].toLong() >= since }
        for (t in TYPES) {
            val ev = todayEv.filter { it[1] == t }
            today.put(t, JSONObject().put("n", ev.size).put("b", ev.sumOf { it[2].toLong() }))
            total.put(t, JSONObject().put("n", p.getInt("n_$t", 0)).put("b", p.getLong("b_$t", 0)))
        }
        return JSONObject().put("today", today).put("total", total)
    }

    /** 최근 것부터 파일 목록 */
    fun list(ctx: Context, type: String, todayOnly: Boolean): JSONArray {
        val since = if (todayOnly) startOfToday() else 0
        return JSONArray(events(ctx).filter { it[1] == type && it[0].toLong() >= since }.asReversed().take(500).map {
            JSONObject().put("t", it[0].toLong()).put("b", it[2].toLong()).put("name", it[3])
        })
    }
}
