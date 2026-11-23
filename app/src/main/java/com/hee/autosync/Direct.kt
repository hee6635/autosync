package com.hee.autosync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.StatFs
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 같은 와이파이에서 보내는 폰 → 받는 폰 직접 전송.
 * 순서: 보내는 쪽 헤더 한 줄(JSON) + 파일 → 받는 쪽 "MD5 <hex>" → 보내는 쪽 "OK"/"NO" → 받는 쪽 "DONE".
 * 받는 쪽은 OK를 받아야 파일을 DCIM/Camera에 넣고, 보내는 쪽은 DONE을 받아야 폰에서 지움.
 */
object DirectProto {
    fun readLine(input: InputStream): String {
        val b = java.io.ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) throw IOException("연결 끊김")
            if (c == '\n'.code) break
            b.write(c)
            if (b.size() > 65536) throw IOException("줄이 너무 김")
        }
        return b.toString("UTF-8")
    }

    private fun writeLine(out: OutputStream, s: String) { out.write((s + "\n").toByteArray()); out.flush() }

    /** 보내기. 받는 쪽이 저장까지 끝내면 true. */
    fun send(sock: Socket, header: JSONObject, data: InputStream, progress: (Long) -> Unit): Boolean {
        val input = BufferedInputStream(sock.getInputStream())
        val out = BufferedOutputStream(sock.getOutputStream(), 1 shl 16)
        out.write((header.toString() + "\n").toByteArray())
        val md = MessageDigest.getInstance("MD5")
        data.use { copy(DigestInputStream(it, md), out, progress) }
        out.flush()
        val reply = readLine(input)
        if (!reply.startsWith("MD5 ")) throw IOException("받는 폰: $reply")
        val ok = reply.substring(4) == hex(md.digest())
        writeLine(out, if (ok) "OK" else "NO")
        return ok && readLine(input) == "DONE"
    }

    /** 받기 한 건. 헤더 확인 → 임시 파일 → MD5 → OK면 이름 바꿔 저장. 저장한 파일을 돌려줌(아니면 null). */
    fun receive(
        sock: Socket, key: String, dir: File, freeBytes: () -> Long, minFree: Long,
        onHeader: (JSONObject, String) -> Unit, progress: (String, Long) -> Unit,
    ): File? {
        val input = BufferedInputStream(sock.getInputStream(), 1 shl 16)
        val out = sock.getOutputStream()
        val h = JSONObject(readLine(input))
        if (h.optString("key") != key) { writeLine(out, "ERR key"); return null }
        val size = h.getLong("size")
        if (size < 0 || freeBytes() - size < minFree) { writeLine(out, "ERR full"); return null }
        val label = h.optString("from").let { if (it.isEmpty()) h.getString("name") else "$it / ${h.getString("name")}" }
        val target = synchronized(receiving) { uniqueFile(dir, safeName(h.getString("name")), receiving).also { receiving += it.name } }
        val tmp = File(dir, ".${target.name}.part")
        try {
            onHeader(h, label)
            val md = MessageDigest.getInstance("MD5")
            tmp.outputStream().buffered(1 shl 16).use { o ->
                val buf = ByteArray(1 shl 16)
                var left = size
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("연결 끊김")
                    o.write(buf, 0, n); md.update(buf, 0, n); left -= n
                    progress(label, size - left)
                }
            }
            writeLine(out, "MD5 " + hex(md.digest()))
            if (readLine(input) != "OK") return null
            h.optLong("modified").let { if (it > 0) tmp.setLastModified(it) } // 영상은 구글포토가 수정 시각으로 날짜를 잡음
            if (!tmp.renameTo(target)) { writeLine(out, "FAIL"); return null }
            writeLine(out, "DONE")
            return target
        } finally {
            tmp.delete()
            synchronized(receiving) { receiving -= target.name }
        }
    }
}

/** 받는 폰이 지금 받고 있는 파일 이름(직접·클라우드 공통). 같은 이름을 동시에 쓰지 않게. */
val receiving = mutableSetOf<String>()

class Peer(val host: InetAddress, val port: Int, val name: String)

object Direct {
    private const val TYPE = "_autosync._tcp"

    /** 두 폰이 같은 구글 계정이라 드라이브 PixelSync 폴더에 비밀 키를 두고 자동으로 짝지음 */
    fun key(s: Settings, drive: Drive, folder: String): String {
        s.directKey?.let { return it }
        val k = drive.getProp(folder, "directKey") ?: UUID.randomUUID().toString().also { drive.setProp(folder, "directKey", it) }
        s.directKey = k
        return k
    }

    fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /** 같은 와이파이에서 받는 폰 찾기 (최대 4초) */
    fun discover(ctx: Context): Peer? {
        val nsd = ctx.getSystemService(NsdManager::class.java)
        val lock = ctx.applicationContext.getSystemService(WifiManager::class.java)
            .createMulticastLock("autosync").apply { setReferenceCounted(false); acquire() }
        val latch = CountDownLatch(1)
        var found: Peer? = null
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (found != null || !info.serviceType.startsWith(TYPE)) return
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        if (found == null) { found = Peer(r.host, r.port, r.serviceName.removePrefix("AutoSync-")); latch.countDown() }
                    }
                    override fun onResolveFailed(i: NsdServiceInfo, code: Int) {}
                })
            }
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
        }
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            latch.await(4, TimeUnit.SECONDS)
        } finally {
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
            lock.release()
        }
        return found
    }

    /** 한 파일 직접 보내기. 받는 폰이 저장까지 끝냈으면 true. */
    fun send(peer: Peer, key: String, from: String, d: Doc, batch: JSONObject, data: InputStream, progress: (Long) -> Unit): Boolean =
        Socket().use { sock ->
            sock.connect(InetSocketAddress(peer.host, peer.port), 5_000)
            sock.soTimeout = 60_000
            val h = JSONObject().put("key", key).put("from", from).put("name", d.name)
                .put("size", d.size).put("modified", d.modified).put("batch", batch)
            DirectProto.send(sock, h, data, progress)
        }
}

/** 받는 폰: 같은 와이파이에서 직접 받는 창구. 알림을 띄워 둔 채로 계속 대기. */
class DirectService : Service() {
    private var server: ServerSocket? = null
    private var nsd: NsdManager.RegistrationListener? = null
    @Volatile private var batch = ""
    @Volatile private var lastActive = 0L
    @Volatile private var got = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(2, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (server == null) thread { start() }
        return START_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("direct", "직접 받기", NotificationManager.IMPORTANCE_MIN))
        return NotificationCompat.Builder(this, "direct").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("직접 받기 대기 중").setOngoing(true).build()
    }

    private fun start() {
        val s = Settings(this)
        val key = try {
            val drive = Drive { Drive.token(this) ?: throw IOException("구글 로그인 필요") }
            Direct.key(s, drive, s.folderId ?: drive.folderId().also { s.folderId = it })
        } catch (e: Exception) { log(this, "직접 받기 시작 실패: ${e.message}"); stopSelf(); return }
        val ss = ServerSocket(0).also { server = it }
        val info = NsdServiceInfo().apply { serviceName = "AutoSync-${Build.MODEL}"; serviceType = "_autosync._tcp"; port = ss.localPort }
        nsd = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Status.listening = true }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { log(this@DirectService, "직접 받기 등록 실패 $code") }
            override fun onServiceUnregistered(i: NsdServiceInfo) { Status.listening = false }
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }.also { getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, it) }
        thread { watch() }
        val dir = cameraDir().apply { mkdirs() }
        while (!ss.isClosed) {
            val sock = try { ss.accept() } catch (e: IOException) { break }
            thread { handle(sock, key, dir, s) }
        }
    }

    private fun handle(sock: Socket, key: String, dir: File, s: Settings) = sock.use {
        it.soTimeout = 60_000
        var label = ""; var size = 0L
        try {
            val f = DirectProto.receive(it, key, dir, { StatFs(dir.path).availableBytes }, s.minFreeGb * 1024L * 1024 * 1024,
                onHeader = { h, l -> label = l; size = h.getLong("size"); begin(h, l, s) },
                progress = { l, n -> Status.items[l]?.set(0, n); lastActive = System.currentTimeMillis() })
            if (f != null) {
                MediaScannerConnection.scanFile(this, arrayOf(f.path), null, null)
                Stats.add(this, "down", f.name, f.length())
                got++
            }
        } catch (e: Exception) {
            log(this, "직접 받기 실패 $label: ${e.message}")
        } finally {
            if (label.isNotEmpty()) Status.done(label, size)
            lastActive = System.currentTimeMillis()
        }
    }

    // shortcut: 직접 받기와 클라우드 받기가 동시에 돌면 진행 표시(Status)를 같이 써서 숫자가 섞임. 둘이 자주 겹치면 나눌 것.
    @Synchronized private fun begin(h: JSONObject, label: String, s: Settings) {
        val b = h.optJSONObject("batch")
        if (b != null && b.optString("id") != batch) {
            batch = b.optString("id"); got = 0
            Status.start(b.optInt("count"), b.optLong("bytes"))
            Status.busy = true; Status.step = 4; Status.note = ""
            Status.route = "direct:" + h.optString("from")
            s.lastPeer = h.optString("from")
        }
        Status.begin(label, h.getLong("size"))
        lastActive = System.currentTimeMillis()
    }

    /** 15초 동안 아무것도 안 오면 한 묶음이 끝난 것으로 봄 */
    private fun watch() {
        while (server?.isClosed == false) {
            Thread.sleep(3_000)
            if (batch.isNotEmpty() && Status.items.isEmpty() && System.currentTimeMillis() - lastActive > 15_000) {
                batch = ""
                Status.stop(); Status.busy = false; Status.step = -1; Status.route = ""
                Status.note = "완료 · ${got}개 받음"
            }
        }
    }

    override fun onDestroy() {
        try { nsd?.let { getSystemService(NsdManager::class.java).unregisterService(it) } } catch (_: Exception) {}
        server?.close()
        Status.listening = false
        super.onDestroy()
    }

    companion object {
        /** 받는 폰이고 "직접 받기"가 켜져 있으면 창구를 열고, 아니면 닫음 */
        fun sync(ctx: Context) {
            val s = Settings(ctx)
            val i = Intent(ctx, DirectService::class.java)
            try {
                if (s.receiver && s.direct) ctx.startForegroundService(i) else ctx.stopService(i)
            } catch (e: Exception) { log(ctx, "직접 받기 시작 못 함: ${e.message}") }
        }
    }
}
