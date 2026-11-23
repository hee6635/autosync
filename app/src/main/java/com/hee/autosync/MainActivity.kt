package com.hee.autosync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.Identity
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private lateinit var s: Settings

    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        refresh()
        schedule(this)
    }

    private val addFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        persist(uri)
        s.autoTrees = s.autoTrees + uri.toString()
        refresh()
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        persist(uri)
        s.pending = s.pending + uri.toString()
        syncNow(this)
        refresh()
    }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        uris.forEach(::persist)
        s.pending = s.pending + uris.map { it.toString() }
        syncNow(this)
        refresh()
    }

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        s = Settings(this)

        findViewById<RadioGroup>(R.id.modeGroup).apply {
            check(if (s.receiver) R.id.modeReceiver else R.id.modeSender)
            setOnCheckedChangeListener { _, id -> s.receiver = id == R.id.modeReceiver; refresh() }
        }
        bind(R.id.wifiOnly, s.wifiOnly) { s.wifiOnly = it; schedule(this) }
        bind(R.id.chargingOnly, s.chargingOnly) { s.chargingOnly = it; schedule(this) }
        bind(R.id.deleteAfter, s.deleteAfter) { s.deleteAfter = it }

        click(R.id.signIn) { signIn() }
        // DCIM/Camera에서 시작. SD카드는 선택 화면 메뉴에서 고르면 됨.
        click(R.id.addFolder) { addFolder.launch(DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:DCIM/Camera")) }
        click(R.id.clearFolders) {
            s.autoTrees.forEach { release(Uri.parse(it)) }
            s.autoTrees = emptySet()
            refresh()
        }
        click(R.id.pickFiles) { pickFiles.launch(arrayOf("image/*", "video/*")) }
        click(R.id.pickFolder) { pickFolder.launch(null) }
        click(R.id.storagePerm) { askPerms.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) }
        click(R.id.syncNow) { syncNow(this); log(this, "지금 실행 요청"); refresh() }

        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS))
            askPerms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        schedule(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun signIn() {
        Identity.getAuthorizationClient(this).authorize(Drive.request())
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) authLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                else { refresh(); schedule(this) }
            }
            .addOnFailureListener { log(this, "로그인 실패: ${it.message}"); refresh() }
    }

    private fun refresh() {
        val receiver = s.receiver
        findViewById<View>(R.id.senderBox).visibility = if (receiver) View.GONE else View.VISIBLE
        findViewById<View>(R.id.receiverBox).visibility = if (receiver) View.VISIBLE else View.GONE

        findViewById<TextView>(R.id.autoFolders).text =
            s.autoTrees.joinToString("\n") { Uri.decode(it).substringAfterLast("/tree/") }.ifEmpty { "(없음) — 폴더 추가로 DCIM/Camera를 고르세요" }

        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val free = StatFs(Environment.getExternalStorageDirectory().path).availableBytes / (1L shl 30)
        findViewById<TextView>(R.id.storage).text = "여유 공간 ${free}GB (${s.minFreeGb}GB 미만이면 받기 중지)\n저장 위치: ${dcim.path}/Camera"
        findViewById<View>(R.id.storagePerm).visibility =
            if (Build.VERSION.SDK_INT <= 29 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) View.VISIBLE else View.GONE

        val status = findViewById<TextView>(R.id.accountStatus)
        thread {
            val ok = try { Drive.token(this) != null } catch (e: Exception) { false }
            runOnUiThread {
                status.text = if (ok) "연결됨 · 드라이브 폴더: ${Drive.FOLDER}" else "로그인 필요"
                findViewById<TextView>(R.id.logView).text = readLog(this)
            }
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

    private fun click(id: Int, f: () -> Unit) = findViewById<Button>(id).setOnClickListener { f() }

    private fun bind(id: Int, value: Boolean, set: (Boolean) -> Unit) = findViewById<CheckBox>(id).apply {
        isChecked = value
        setOnCheckedChangeListener { _, v -> set(v) }
    }
}
