package com.hee.autosync

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.Identity
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/** 화면은 assets/의 HTML(웹뷰), 여기는 설정·작업과 이어 주는 다리(Bridge)만. */
class MainActivity : ComponentActivity() {
    private lateinit var s: Settings
    private lateinit var web: WebView

    // 드라이브·폴더에서 읽어 온 값. 화면은 state()로 가져감.
    @Volatile private var signedIn = false
    @Volatile private var quota: JSONObject? = null
    @Volatile private var waiting: JSONObject = JSONObject().put("n", 0).put("b", 0)

    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { reload() }

    private val addFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        persist(uri)
        s.autoTrees = s.autoTrees + uri.toString()
        reload()
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        persist(uri)
        s.pending = s.pending + uri.toString()
        syncNow(this); reload()
    }
    /** 파일 고르기: 갤러리·파일 관리자 등 고르는 앱을 사용자가 선택 */
    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data ?: return@registerForActivityResult
        val c = d.clipData
        addPending(if (c != null) (0 until c.itemCount).map { c.getItemAt(it).uri } else listOfNotNull(d.data))
    }
    /** 보낸 뒤 공유로 받은 파일 지우기: 안드로이드 기본 삭제 확인창 */
    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val items = s.toDelete
        if (r.resultCode == RESULT_OK) items.forEach { e -> e.split('\t').let { Stats.add(this, "delLocal", it.getOrElse(2) { "" }, it.getOrElse(1) { "0" }.toLong()) } }
        else log(this, "폰에서 지우기 안 함 ${items.size}개")
        s.toDelete = emptySet()
        reload()
    }

    // shortcut: 공유·다른 앱에서 받은 파일 권한은 이 화면(작업)이 살아 있는 동안만 유효할 수 있음. 앱을 완전히 닫으면 못 읽을 수 있어 바로 보냄.
    private fun addPending(uris: List<Uri>) {
        if (uris.isEmpty()) return
        uris.forEach(::persist)
        s.pending = s.pending + uris.map { it.toString() }
        log(this, "보낼 파일 ${uris.size}개 추가")
        syncNow(this); reload()
    }

    /** 갤러리 등에서 공유 → Auto Sync */
    private fun handleShare(i: Intent?) {
        @Suppress("DEPRECATION")
        val uris = when (i?.action) {
            Intent.ACTION_SEND -> listOfNotNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> return
        }
        i.action = null // 화면을 돌려도 두 번 추가하지 않게
        if (s.receiver) { log(this, "받는 폰 모드라 공유한 파일은 보내지 않음"); return }
        addPending(uris)
    }

    // 보내는 폰 화면: 같은 와이파이에 받는 폰이 보이는지 (화면이 열려 있을 때 30초마다)
    @Volatile private var probed = false
    @Volatile private var probePeer = ""
    @Volatile private var probing = false
    private fun probe() {
        if (probing || s.receiver || !s.directSend) return
        probing = true
        thread { probeNow(); emit("reload", "true") }
    }
    /** 받는 폰 찾고, 찾으면 키(코드)가 맞는지 한 번 물어봄 */
    private fun probeNow() {
        probing = true
        try {
            val peer = try { if (Direct.onWifi(this)) Direct.discover(this) else null } catch (e: Exception) { null }
            probePeer = peer?.name ?: ""
            val key = peer?.let { Direct.sendKey(s, null, null) }
            if (peer != null && key != null) Direct.ping(peer, key)?.let { s.keyBad = !it }
            probed = true
        } finally { probing = false }
    }

    private fun confirmDelete() {
        if (Build.VERSION.SDK_INT < 30 || s.toDelete.isEmpty()) return
        val uris = s.toDelete.map { Uri.parse(it.substringBefore('\t')) }
        try {
            deleteLauncher.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(contentResolver, uris).intentSender).build())
        } catch (e: Exception) { log(this, "삭제 확인창 실패: ${e.message}"); s.toDelete = emptySet() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = Settings(this)
        window.statusBarColor = Color.parseColor("#F2F4F6")
        window.navigationBarColor = Color.parseColor("#F2F4F6")
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(Color.parseColor("#F2F4F6"))
            addJavascriptInterface(Bridge(), "A")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.app&&app.back()") { if (it != "true") finish() }
            }
        })
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS))
            askPerms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        schedule(this)
        DirectService.sync(this)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    /** 로그인·용량·대기 개수를 새로 읽고 화면에 알림 */
    private fun reload() = thread { reloadNow(); emit("reload", "true") }
    private fun reloadNow() {
        signedIn = try { Drive.token(this) != null } catch (e: Exception) { false }
        try {
            val drive = if (signedIn) Drive { Drive.token(this)!! } else null
            if (drive != null) {
                val (limit, usage) = drive.quota()
                quota = JSONObject().put("limit", limit ?: -1).put("usage", usage)
            }
            waiting = if (!s.receiver) {
                val j = pendingJobs(this, s, manual = true)
                JSONObject().put("n", j.size).put("b", j.sumOf { it.doc.size })
            } else if (drive != null) {
                val folder = s.folderId ?: drive.folderId().also { s.folderId = it }
                val r = remoteJobs(drive, folder, s)
                JSONObject().put("n", r.size).put("b", r.sumOf { it.r.size })
            } else waiting
        } catch (e: Exception) { log(this, "새로고침 실패: ${e.message}") }
    }

    /** 당겨서 새로 고침: 상태 다시 읽기 + (보내는 폰) 받는 폰 다시 찾기. 끝나면 app.on('refreshed') */
    @Volatile private var refreshing = false
    private fun pullRefresh() {
        if (refreshing) return
        refreshing = true
        thread {
            try {
                val t = thread { reloadNow() }
                if (!s.receiver && s.directSend) { while (probing) Thread.sleep(100); probeNow() }
                t.join()
            } finally { refreshing = false; emit("refreshed", "true") }
        }
    }

    private fun emit(ev: String, json: String) = runOnUiThread {
        web.evaluateJavascript("window.app&&app.on('$ev',$json)", null)
    }

    private fun folderLabel(uri: String): String {
        val id = Uri.decode(uri).substringAfterLast("/tree/").substringBefore("/document/")
        val vol = id.substringBefore(':')
        val path = id.substringAfter(':', "").ifEmpty { "전체" }
        return if (vol == "primary") path else "SD카드 / $path"
    }

    private fun minutes(v: String): Pair<Int, Int> =
        v.split(',').mapNotNull { it.trim().toIntOrNull() }.let { if (it.size == 2) it[0] to it[1] else -1 to -1 }

    inner class Bridge {
        @JavascriptInterface
        fun state(): String {
            val cm = getSystemService(ConnectivityManager::class.java)
            val canRun = signedIn || s.codeOnly
            val note = when {
                Status.busy -> Status.note
                !canRun -> "로그인 필요"
                s.wifiOnly && cm.isActiveNetworkMetered -> "와이파이 대기"
                s.chargingOnly && !getSystemService(android.os.BatteryManager::class.java).isCharging -> "충전 대기"
                Status.note.isNotEmpty() -> Status.note
                !s.auto -> "자동 꺼짐 · 버튼으로만"
                else -> ""
            }
            return JSONObject()
                .put("receiver", s.receiver).put("signedIn", signedIn).put("canRun", canRun).put("auto", s.auto)
                .put("pairCode", s.pairCode).put("peerCode", s.peerCode ?: "").put("keyBad", s.keyBad)
                .put("direct", s.direct).put("lastPeer", s.lastPeer).put("directSend", s.directSend)
                .put("probe", JSONObject().put("checked", probed).put("peer", probePeer))
                .put("recvFolders", JSONArray(s.recvFolders.map { JSONObject().put("id", it.id).put("name", it.name).put("del", it.del) })).put("toDelete", s.toDelete.size)
                .put("batteryOk", getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
                .put("askedBattery", getSharedPreferences("settings", MODE_PRIVATE).getBoolean("askedBattery", false))
                .put("wifiOnly", s.wifiOnly).put("chargingOnly", s.chargingOnly)
                .put("deleteAfter", s.deleteAfter).put("askedDelete", s.askedDelete)
                .put("concurrency", s.concurrency).put("minFreeGb", s.minFreeGb)
                .put("up", JSONArray(listOf(s.upStart, s.upEnd))).put("down", JSONArray(listOf(s.downStart, s.downEnd)))
                .put("folders", JSONArray(s.autoTrees.sorted().map {
                    JSONObject().put("uri", it).put("label", folderLabel(it)).put("sub", s.sub(it))
                }))
                .put("defaultSub", s.defaultSub())
                .put("quota", quota ?: JSONObject.NULL)
                .put("phoneFree", StatFs(Environment.getExternalStorageDirectory().path).availableBytes)
                .put("needStorage", Build.VERSION.SDK_INT <= 29 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE))
                .put("waiting", waiting)
                .put("status", Status.json().put("note", note))
                .put("stats", Stats.summary(this@MainActivity))
                .toString()
        }

        @JavascriptInterface
        fun set(key: String, value: String) {
            val on = value == "true"
            when (key) {
                "receiver" -> { s.receiver = on; DirectService.sync(this@MainActivity); reload() }
                "direct" -> { s.direct = on; DirectService.sync(this@MainActivity) }
                "directSend" -> { s.directSend = on; probed = false; probePeer = "" }
                "peerCode" -> { s.peerCode = value.filter { it.isDigit() }.takeIf { it.length == 6 }; s.keyBad = false; probed = false; probePeer = "" }
                "auto" -> { s.auto = on; Status.note = "" }
                "wifiOnly" -> s.wifiOnly = on
                "chargingOnly" -> s.chargingOnly = on
                "deleteAfter" -> { val first = !s.askedDelete; s.deleteAfter = on; s.askedDelete = true; if (first && !s.receiver) syncNow(this@MainActivity) }
                "concurrency" -> s.concurrency = value.toInt()
                "minFreeGb" -> s.minFreeGb = value.toInt()
                "up" -> minutes(value).let { s.upStart = it.first; s.upEnd = it.second }
                "down" -> minutes(value).let { s.downStart = it.first; s.downEnd = it.second }
            }
            schedule(this@MainActivity)
        }

        /** 폰 폴더 → 드라이브 하위 폴더 이름 */
        @JavascriptInterface
        fun setSub(uri: String, name: String) {
            val n = name.replace('/', '_').trim()
            if (n.isNotEmpty()) s.setSub(uri, n)
        }

        /** 드라이브 폴더 고르기: parent 아래 폴더들 → app.on('dfolders', {parent, items}) */
        @JavascriptInterface
        fun driveList(parent: String) {
            thread {
                val items = try {
                    Drive { Drive.token(this@MainActivity)!! }.subfolders(parent).sortedBy { it.second.lowercase() }
                        .map { JSONObject().put("id", it.first).put("name", it.second) }
                } catch (e: Exception) { log(this@MainActivity, "드라이브 폴더 목록 실패: ${e.message}"); emptyList() }
                emit("dfolders", JSONObject().put("parent", parent).put("items", JSONArray(items)).toString())
            }
        }

        @JavascriptInterface
        fun addRecvFolder(id: String, name: String, del: Boolean) {
            s.recvFolders = s.recvFolders.filter { it.id != id } + RecvFolder(id, name, del)
            reload()
        }

        @JavascriptInterface
        fun setRecvDel(id: String, del: Boolean) { s.recvFolders = s.recvFolders.map { if (it.id == id) RecvFolder(it.id, it.name, del) else it } }

        @JavascriptInterface
        fun removeRecvFolder(id: String) { s.recvFolders = s.recvFolders.filter { it.id != id }; reload() }

        @JavascriptInterface
        fun recent(type: String, today: Boolean): String = Stats.list(this@MainActivity, type, today).toString()

        @JavascriptInterface
        fun logText(): String = readLog(this@MainActivity)

        /** PixelSync 아래 하위 폴더 이름들 → app.on('subs', [...]) */
        @JavascriptInterface
        fun subs() {
            thread {
                val names = try {
                    val drive = Drive { Drive.token(this@MainActivity)!! }
                    val folder = s.folderId ?: drive.folderId().also { s.folderId = it }
                    drive.subfolders(folder).map { it.second }
                } catch (e: Exception) { emptyList() }
                emit("subs", JSONArray(names).toString())
            }
        }

        @JavascriptInterface
        fun act(name: String, arg: String) = runOnUiThread {
            when (name) {
                "signIn" -> signIn()
                "cancel" -> cancelSync()
                "probe" -> probe()
                "refresh" -> pullRefresh()
                "battery" -> askBattery()
                "batteryOnce" -> {
                    val p = getSharedPreferences("settings", MODE_PRIVATE)
                    if (!p.getBoolean("askedBattery", false)) { p.edit().putBoolean("askedBattery", true).apply(); askBattery() }
                }
                "syncNow" -> { syncNow(this@MainActivity); log(this@MainActivity, "지금 실행 요청") }
                // DCIM/Camera에서 시작. SD카드는 선택 화면 메뉴에서 고르면 됨.
                "addFolder" -> addFolder.launch(DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:DCIM/Camera"))
                "removeFolder" -> { release(Uri.parse(arg)); s.autoTrees = s.autoTrees - arg; reload() }
                "pickFiles" -> pickFiles.launch(Intent.createChooser(
                    Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), "어디서 고를까요?"))
                "confirmDelete" -> confirmDelete()
                "pickFolder" -> pickFolder.launch(null)
                "storage" -> askPerms.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
                "reload" -> reload()
                "copy" -> getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("autosync", readLog(this@MainActivity)))
            }
        }
    }

    private fun signIn() {
        Identity.getAuthorizationClient(this).authorize(Drive.request())
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) authLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                else reload()
            }
            .addOnFailureListener { log(this, "로그인 실패: ${it.message}"); reload() }
    }

    @SuppressLint("BatteryLife") // 개인용 앱: 백그라운드 전송이 끊기지 않게 직접 요청
    private fun askBattery() {
        if (getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun persist(uri: Uri) {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try { contentResolver.takePersistableUriPermission(uri, rw) } catch (_: SecurityException) {
            // 쓰기 권한을 안 주는 앱에서 고른 파일: 보내기는 되지만 삭제는 안 됨
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) {}
        }
    }

    private fun release(uri: Uri) {
        try { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        catch (_: SecurityException) {}
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
}
