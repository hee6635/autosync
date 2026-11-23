package com.hee.autosync

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var receiver: Boolean
        get() = p.getBoolean("receiver", false)
        set(v) = p.edit().putBoolean("receiver", v).apply()
    var wifiOnly: Boolean
        get() = p.getBoolean("wifiOnly", true)
        set(v) = p.edit().putBoolean("wifiOnly", v).apply()
    var chargingOnly: Boolean
        get() = p.getBoolean("chargingOnly", false)
        set(v) = p.edit().putBoolean("chargingOnly", v).apply()
    var deleteAfter: Boolean
        get() = p.getBoolean("deleteAfter", true)
        set(v) = p.edit().putBoolean("deleteAfter", v).apply()
    var minFreeGb: Int
        get() = p.getInt("minFreeGb", 10)
        set(v) = p.edit().putInt("minFreeGb", v).apply()

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
    var folderId: String?
        get() = p.getString("folderId", null)
        set(v) = p.edit().putString("folderId", v).apply()
}

private val logLock = Any()

fun log(ctx: Context, msg: String) = synchronized(logLock) {
    val f = File(ctx.filesDir, "log.txt")
    val time = SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(Date())
    val lines = (if (f.exists()) f.readLines() else emptyList()) + "$time $msg"
    f.writeText(lines.takeLast(200).joinToString("\n"))
}

fun readLog(ctx: Context): String =
    File(ctx.filesDir, "log.txt").takeIf { it.exists() }?.readLines()?.reversed()?.joinToString("\n") ?: ""
