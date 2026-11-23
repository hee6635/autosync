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
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        uris.forEach(::persist)
        s.pending = s.pending + uris.map { it.toString() }
        syncNow(this); reload()
    }
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = Settings(this)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(Color.WHITE)
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
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    /** 로그인·용량·대기 개수를 새로 읽고 화면에 알림 */
    private fun reload() = thread {
        signedIn = try { Drive.token(this) != null } catch (e: Exception) { false }
        if (signedIn) try {
            val drive = Drive { Drive.token(this)!! }
            val (limit, usage) = drive.quota()
            quota = JSONObject().put("limit", limit ?: -1).put("usage", usage)
            waiting = if (s.receiver) {
                val folder = s.folderId ?: drive.folderId().also { s.folderId = it }
                val r = remoteJobs(drive, folder)
                JSONObject().put("n", r.size).put("b", r.sumOf { it.second.size })
            } else {
                val j = pendingJobs(this, s, manual = true)
                JSONObject().put("n", j.size).put("b", j.sumOf { it.doc.size })
            }
        } catch (e: Exception) { log(this, "새로고침 실패: ${e.message}") }
        emit("reload", "true")
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
            val note = when {
                Status.busy -> Status.note
                !signedIn -> "로그인 필요"
                s.wifiOnly && cm.isActiveNetworkMetered -> "와이파이 대기"
                s.chargingOnly && !getSystemService(android.os.BatteryManager::class.java).isCharging -> "충전 대기"
                Status.note.isNotEmpty() -> Status.note
                !s.auto -> "자동 꺼짐 · 버튼으로만"
                else -> ""
            }
            return JSONObject()
                .put("receiver", s.receiver).put("signedIn", signedIn).put("auto", s.auto)
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
                "receiver" -> { s.receiver = on; reload() }
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
                "syncNow" -> { syncNow(this@MainActivity); log(this@MainActivity, "지금 실행 요청") }
                // DCIM/Camera에서 시작. SD카드는 선택 화면 메뉴에서 고르면 됨.
                "addFolder" -> addFolder.launch(DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:DCIM/Camera"))
                "removeFolder" -> { release(Uri.parse(arg)); s.autoTrees = s.autoTrees - arg; reload() }
                "pickFiles" -> pickFiles.launch(arrayOf("image/*", "video/*"))
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
