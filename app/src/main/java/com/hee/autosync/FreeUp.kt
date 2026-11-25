package com.hee.autosync

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** 받는 폰이 받은 파일 중 아직 DCIM/Camera에 남은 것. 사라지면 = 구글포토 공간 확보로 지워짐. */
object Kept {
    private fun file(ctx: Context) = File(ctx.filesDir, "kept.tsv")

    private fun read(ctx: Context): List<List<String>> =
        file(ctx).takeIf { it.exists() }?.readLines()?.mapNotNull { l -> l.split('\t').takeIf { it.size == 3 } } ?: emptyList()

    @Synchronized fun add(ctx: Context, f: File) {
        file(ctx).appendText("${f.name.replace('\t', ' ')}\t${f.length()}\t${System.currentTimeMillis()}\n")
        Settings(ctx).freeUpChanged = System.currentTimeMillis()
    }

    /** 사라진 파일을 "장치에서 삭제"로 기록하고 목록에서 뺌. 사라진 개수를 돌려줌. */
    @Synchronized fun check(ctx: Context): Int {
        val all = read(ctx)
        if (all.isEmpty()) return 0
        val dir = cameraDir()
        val (gone, left) = all.partition { !File(dir, it[0]).exists() }
        if (gone.isEmpty()) return 0
        gone.forEach { Stats.add(ctx, "delLocal", it[0], it[1].toLong()) }
        file(ctx).writeText(left.joinToString("") { it.joinToString("\t") + "\n" })
        Settings(ctx).freeUpChanged = System.currentTimeMillis()
        return gone.size
    }

    @Synchronized fun summary(ctx: Context): Pair<Int, Long> = read(ctx).let { l -> l.size to l.sumOf { it[1].toLong() } }
}

/**
 * 구글포토 "기기 공간 확보"를 대신 누름 (접근성 서비스).
 * 느린 폰 대비: 시간을 정해 두지 않고, 버튼이 화면에 뜰 때까지 기다렸다가(단계마다 최대 60~90초) 누름.
 * 성공 여부는 누르기가 아니라 받은 파일이 실제로 사라졌는지(Kept.check)로만 판단.
 */
class FreeUpService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        // 화면 구조 기록 중이면 구글포토 화면이 바뀔 때마다 저장 (1초에 한 번까지)
        if (System.currentTimeMillis() < recordUntil && e?.packageName == PHOTOS && System.currentTimeMillis() - lastDump > 1000) {
            lastDump = System.currentTimeMillis()
            dump("기록")
        }
    }

    private fun roots(): List<AccessibilityNodeInfo> =
        (try { windows.mapNotNull { it.root } } catch (e: Exception) { emptyList() }).ifEmpty { listOfNotNull(rootInActiveWindow) }

    private fun all(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo> = mutableListOf()): MutableList<AccessibilityNodeInfo> {
        if (n == null) return out
        out += n
        for (i in 0 until n.childCount) all(n.getChild(i), out)
        return out
    }

    private fun label(n: AccessibilityNodeInfo) = "${n.text ?: ""} ${n.contentDescription ?: ""}".trim()

    /** 조건에 맞는 노드가 뜰 때까지 기다림 */
    private fun waitFor(sec: Int, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + sec * 1000L
        while (System.currentTimeMillis() < end) {
            roots().forEach { r -> all(r).firstOrNull(test)?.let { return it } }
            Thread.sleep(500)
        }
        return null
    }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        // 누르기 동작이 안 먹으면 그 자리를 손가락으로 톡
        val r = Rect().also { n.getBoundsInScreen(it) }
        if (r.isEmpty) return false
        val p = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 80)).build(), null, null)
    }

    private fun inPhotos() = roots().any { it.packageName == PHOTOS }

    private fun dump(why: String) {
        val sb = StringBuilder("--- ${SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(Date())} $why\n")
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null) return
            val r = Rect().also { n.getBoundsInScreen(it) }
            sb.append("  ".repeat(d)).append(n.className?.toString()?.substringAfterLast('.'))
            n.viewIdResourceName?.let { sb.append(" #").append(it.substringAfter(":id/")) }
            label(n).takeIf { it.isNotEmpty() }?.let { sb.append(" \"").append(it.replace('\n', ' ')).append('"') }
            if (n.isClickable) sb.append(" [누름]")
            sb.append(' ').append(r.toShortString()).append('\n')
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        roots().forEach { walk(it, 0) }
        val f = screenFile(this)
        val old = if (f.exists() && f.length() < 400_000) f.readText() else ""
        f.writeText(old + sb)
    }

    private fun run(manual: Boolean) {
        val s = Settings(this)
        val pm = getSystemService(PowerManager::class.java)
        @Suppress("DEPRECATION")
        val wake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "autosync:freeup")
        var step = ""
        try {
            wake.acquire(5 * 60_000L)
            state = "구글포토 여는 중"; step = "구글포토 열기"
            if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("wake", true))
                Thread.sleep(3000)
            }
            val launch = packageManager.getLaunchIntentForPackage(PHOTOS) ?: throw IllegalStateException("구글포토 없음")
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val end = System.currentTimeMillis() + 60_000
            while (!inPhotos() && System.currentTimeMillis() < end) Thread.sleep(500)
            if (!inPhotos()) throw IllegalStateException("안 열림(화면 잠금?)")

            state = "공간 확보 누르는 중"; step = "계정 버튼"
            val account = waitFor(60) { n -> n.packageName == PHOTOS && label(n).let { it.contains("계정") || it.contains("Account", true) } }
                ?: throw IllegalStateException("못 찾음")
            click(account)
            step = "기기 공간 확보 메뉴"
            val menu = waitFor(60) { n -> label(n).contains("공간 확보") || label(n).contains("Free up", true) }
                ?: throw IllegalStateException("못 찾음")
            Thread.sleep(500)
            click(menu)

            // 다음 화면: 지울 것이 없다는 글 또는 "N GB 확보"/"삭제" 버튼 (계산이 느릴 수 있어 90초)
            state = "확인 중"; step = "확보 버튼"
            val menuText = label(menu)
            val next = waitFor(90) { n ->
                val t = label(n)
                (n.isClickable || n.className?.contains("Button") == true) && t != menuText &&
                    (t.contains("확보") || t.contains("삭제") || t.contains("Free up", true) || t.contains("Delete", true)) ||
                    t.contains("없습니다") || t.contains("Nothing", true) || t.contains("already", true)
            } ?: throw IllegalStateException("못 찾음")
            val t = label(next)
            if (t.contains("없습니다") || t.contains("Nothing", true) || t.contains("already", true)) {
                log(this, "공간 확보: 지울 것 없음")
            } else {
                click(next)
                // 지우는 시간: 받은 파일이 사라지기 시작할 때까지 최대 2분 기다림
                state = "지우는 중"
                val until = System.currentTimeMillis() + 120_000
                var gone = 0
                while (System.currentTimeMillis() < until) {
                    Thread.sleep(3000)
                    gone += Kept.check(this)
                    if (gone > 0 && Kept.summary(this).first == 0) break
                }
                Thread.sleep(3000)
            }
            val gone = Kept.check(this) // 위에서 센 것 외에 늦게 사라진 것
            val left = Kept.summary(this).first
            s.freeUpResult = "${hm()} · 남음 ${left}개"
            log(this, "공간 확보 끝 · 남은 받은 파일 ${left}개" + if (gone > 0) " (+$gone)" else "")
            s.freeUpFails = 0
        } catch (e: Exception) {
            dump("실패: $step")
            s.freeUpFails = s.freeUpFails + 1
            s.freeUpResult = "${hm()} · 실패($step)"
            log(this, "공간 확보 실패 [$step] ${e.message} · 화면 구조 기록됨")
            if (s.freeUpFails == 3) notifyFail(step)
        } finally {
            s.freeUpLast = System.currentTimeMillis()
            state = ""
            running = false
            // Auto Sync로 돌아옴
            try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            if (wake.isHeld) wake.release()
        }
    }

    private fun notifyFail(step: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("freeup", "공간 확보", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(3, androidx.core.app.NotificationCompat.Builder(this, "freeup").setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("공간 확보 3번 실패 · $step").setAutoCancel(true).build())
    }

    private fun hm() = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date())

    companion object {
        const val PHOTOS = "com.google.android.apps.photos"
        @Volatile var instance: FreeUpService? = null
        @Volatile var state = ""
        @Volatile var running = false
        @Volatile var recordUntil = 0L
        @Volatile private var lastDump = 0L

        fun screenFile(ctx: Context) = File(ctx.filesDir, "screen.txt")

        fun enabled(ctx: Context): Boolean {
            val list = android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { ComponentName.unflattenFromString(it)?.let { c -> c.packageName == ctx.packageName } == true }
        }

        /** 지금 누르기. 접근성이 꺼져 있으면 false */
        fun start(ctx: Context, manual: Boolean): Boolean {
            val svc = instance ?: return false
            synchronized(this) { if (running) return true; running = true }
            thread { svc.run(manual) }
            return true
        }

        /** 화면 구조 기록: 2분 동안 구글포토 화면이 바뀔 때마다 저장 */
        fun record(ctx: Context) {
            screenFile(ctx).delete()
            recordUntil = System.currentTimeMillis() + 120_000
        }

        /**
         * 자동 누르기(15분 작업에서 부름): 받은 파일이 남아 있고 받는 중이 아닐 때.
         * 1시간마다, 남은 공간 50GB 이하면 10분마다. 24시간 동안 변화(새로 받음·사라짐)가 없으면 멈춤.
         */
        fun maybe(ctx: Context) {
            val s = Settings(ctx)
            Kept.check(ctx)
            if (Kept.summary(ctx).first == 0 || Status.items.isNotEmpty() || Status.running) return
            val now = System.currentTimeMillis()
            if (now - s.freeUpChanged > 24 * 3600_000L && s.freeUpLast > s.freeUpChanged) return
            val low = android.os.StatFs(cameraDir().path).availableBytes < 50L * 1024 * 1024 * 1024
            if (now - s.freeUpLast < (if (low) 10 else 60) * 60_000L) return
            start(ctx, manual = false)
        }
    }
}
