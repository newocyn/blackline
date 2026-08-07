package online.pcguys.opsec

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("pcg_opsec", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var activityLog: TextView
    private var pendingUri: Uri? = null
    private var clipboardClearRunnable: Runnable? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingUri = uri
            sanitize(uri, autoShare = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        installClipboardGuard()
        refreshPrivacyStatus()
        handleIncomingShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(32))
            setBackgroundColor(Color.BLACK)
        }

        root.addView(TextView(this).apply {
            text = "PCG OPSEC"
            setTextColor(Color.WHITE)
            textSize = 30f
            setTypeface(typeface, 1)
        })
        root.addView(TextView(this).apply {
            text = "Local privacy controls for media, sharing and device hygiene"
            setTextColor(0xFF9A9A9A.toInt())
            textSize = 14f
            setPadding(0, dp(4), 0, dp(16))
        })

        status = TextView(this).apply {
            text = "Ready. All processing stays on this device."
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xFF151515.toInt())
        }
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(section("MEDIA SANITIZATION",
            toggle("Strip photo metadata", "strip_metadata", true, "Re-encodes images so EXIF, GPS, camera and software metadata are not carried into the sanitized copy."),
            toggle("Blur detected faces", "blur_faces", false, "Pixelates detected faces in the sanitized copy."),
            toggle("Redact QR / barcodes", "redact_codes", false, "Covers detected QR codes and barcodes before export."),
            toggle("Redact sensitive visible text", "redact_text", false, "Covers OCR regions that resemble emails, phone numbers, account numbers or addresses."),
            toggle("Use generic filenames", "generic_names", true, "Exports files as randomized OPSEC image names."),
            toggle("Save sanitized copy to gallery", "save_gallery", false, "Also saves the processed image under Pictures/PCG OPSEC.")))

        root.addView(section("SHARING PROTECTION",
            toggle("Auto-sanitize when shared to PCG OPSEC", "auto_share", true, "Choose PCG OPSEC from Android's Share menu and the sanitized result is generated before the next share step."),
            toggle("Delete temporary exports after 10 minutes", "delete_temp", true, "Removes PCG OPSEC cache copies after a short sharing window.")))

        root.addView(section("CLIPBOARD PROTECTION",
            toggle("Auto-clear clipboard after 30 seconds", "clipboard_clear", true, "While PCG OPSEC is running, newly copied content is cleared after 30 seconds."),
            toggle("Warn on sensitive clipboard text", "clipboard_warn", true, "Shows a local warning when copied text resembles credentials, account numbers or other sensitive content.")))

        val actions = card()
        actions.addView(title("QUICK ACTIONS"))
        val sanitizeButton = primaryButton("CHOOSE PHOTO TO SANITIZE") { picker.launch(arrayOf("image/*")) }
        val privacyButton = outlineButton("REFRESH PRIVACY STATUS") { refreshPrivacyStatus() }
        val panicButton = outlineButton("PANIC CLEANUP") { confirmPanic() }
        actions.addView(sanitizeButton)
        actions.addView(privacyButton, marginTop(8))
        actions.addView(panicButton, marginTop(8))
        root.addView(actions, marginTop(14))

        val device = card()
        device.addView(title("DEVICE PRIVACY STATUS"))
        device.addView(TextView(this).apply {
            id = View.generateViewId()
            tag = "privacy_status"
            text = "Checking…"
            setTextColor(0xFFCBCBCB.toInt())
            textSize = 15f
            setPadding(0, dp(10), 0, 0)
        })
        root.addView(device, marginTop(14))

        val logCard = card()
        logCard.addView(title("LOCAL ACTIVITY"))
        activityLog = TextView(this).apply {
            setTextColor(0xFFBDBDBD.toInt())
            textSize = 14f
            setPadding(0, dp(10), 0, 0)
        }
        logCard.addView(activityLog)
        root.addView(logCard, marginTop(14))
        refreshLog()

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun section(name: String, vararg switches: View): View {
        val c = card()
        c.addView(title(name))
        switches.forEach { c.addView(it, marginTop(6)) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(c, marginTop(14))
        }
    }

    private fun toggle(label: String, key: String, default: Boolean, description: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val txt = TextView(this@MainActivity).apply { text = label; setTextColor(Color.WHITE); textSize = 16f }
            val sw = SwitchCompat(this@MainActivity).apply {
                isChecked = prefs.getBoolean(key, default)
                buttonTintList = null
                setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
            }
            row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(sw)
            addView(row)
            addView(TextView(this@MainActivity).apply {
                text = description
                setTextColor(0xFF898989.toInt())
                textSize = 12.5f
                setPadding(0, dp(3), dp(36), 0)
            })
        }
    }

    private fun handleIncomingShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return
        val uri = if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        if (uri != null && prefs.getBoolean("auto_share", true)) {
            pendingUri = uri
            sanitize(uri, autoShare = true)
        }
    }

    private fun sanitize(uri: Uri, autoShare: Boolean) {
        status.text = "Sanitizing image locally…"
        val bitmap = try {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null }
        if (bitmap == null) {
            status.text = "Could not read that image."
            return
        }
        val working = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        processFaces(working) { faceBitmap ->
            processCodes(faceBitmap) { codeBitmap ->
                processSensitiveText(codeBitmap) { finalBitmap ->
                    exportSanitized(finalBitmap, autoShare)
                    if (finalBitmap !== bitmap && !bitmap.isRecycled) bitmap.recycle()
                }
            }
        }
    }

    private fun processFaces(bitmap: Bitmap, done: (Bitmap) -> Unit) {
        if (!prefs.getBoolean("blur_faces", false)) { done(bitmap); return }
        val detector = FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build())
        detector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { faces ->
                faces.forEach { pixelate(bitmap, it.boundingBox) }
                done(bitmap)
            }
            .addOnFailureListener { done(bitmap) }
    }

    private fun processCodes(bitmap: Bitmap, done: (Bitmap) -> Unit) {
        if (!prefs.getBoolean("redact_codes", false)) { done(bitmap); return }
        BarcodeScanning.getClient().process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { codes ->
                val canvas = Canvas(bitmap); val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
                codes.mapNotNull { it.boundingBox }.forEach { canvas.drawRect(it, paint) }
                done(bitmap)
            }
            .addOnFailureListener { done(bitmap) }
    }

    private fun processSensitiveText(bitmap: Bitmap, done: (Bitmap) -> Unit) {
        if (!prefs.getBoolean("redact_text", false)) { done(bitmap); return }
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val canvas = Canvas(bitmap); val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
                result.textBlocks.forEach { block ->
                    if (looksSensitive(block.text)) block.boundingBox?.let { canvas.drawRect(it, paint) }
                }
                done(bitmap)
            }
            .addOnFailureListener { done(bitmap) }
    }

    private fun looksSensitive(text: String): Boolean {
        val s = text.replace("\n", " ")
        val email = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
        val phone = Regex("(?:\\+?1[-. ]?)?\\(?\\d{3}\\)?[-. ]?\\d{3}[-. ]?\\d{4}")
        val longNumber = Regex("\\b(?:\\d[ -]*?){9,19}\\b")
        val address = Regex("\\b\\d{1,6}\\s+[A-Za-z0-9 .'-]+\\s(?:St|Street|Rd|Road|Ave|Avenue|Dr|Drive|Ln|Lane|Blvd|Boulevard|Ct|Court)\\b", RegexOption.IGNORE_CASE)
        return email.containsMatchIn(s) || phone.containsMatchIn(s) || longNumber.containsMatchIn(s) || address.containsMatchIn(s)
    }

    private fun pixelate(bitmap: Bitmap, rect: Rect) {
        val safe = Rect(rect.left.coerceAtLeast(0), rect.top.coerceAtLeast(0), rect.right.coerceAtMost(bitmap.width), rect.bottom.coerceAtMost(bitmap.height))
        if (safe.width() <= 4 || safe.height() <= 4) return
        val crop = Bitmap.createBitmap(bitmap, safe.left, safe.top, safe.width(), safe.height())
        val tiny = Bitmap.createScaledBitmap(crop, (safe.width() / 18).coerceAtLeast(2), (safe.height() / 18).coerceAtLeast(2), false)
        val blocky = Bitmap.createScaledBitmap(tiny, safe.width(), safe.height(), false)
        Canvas(bitmap).drawBitmap(blocky, safe.left.toFloat(), safe.top.toFloat(), Paint())
        crop.recycle(); tiny.recycle(); blocky.recycle()
    }

    private fun exportSanitized(bitmap: Bitmap, autoShare: Boolean) {
        val dir = File(cacheDir, "sanitized").apply { mkdirs() }
        val generic = prefs.getBoolean("generic_names", true)
        val filename = if (generic) "OPSEC-${UUID.randomUUID().toString().take(8)}.jpg" else "sanitized-${System.currentTimeMillis()}.jpg"
        val file = File(dir, filename)
        try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
            if (prefs.getBoolean("save_gallery", false)) saveToGallery(bitmap, filename)
            addLog("Sanitized photo • metadata stripped${if (prefs.getBoolean("blur_faces", false)) " • faces processed" else ""}")
            status.text = "Sanitized copy ready. Original file was not modified."
            if (prefs.getBoolean("delete_temp", true)) handler.postDelayed({ if (file.exists()) file.delete() }, 10 * 60 * 1000L)
            showResultDialog(file, autoShare)
        } catch (e: Exception) {
            status.text = "Sanitization failed: ${e.message ?: "unknown error"}"
        }
    }

    private fun saveToGallery(bitmap: Bitmap, filename: String) {
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PCG OPSEC")
        }
        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { outUri ->
            contentResolver.openOutputStream(outUri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        }
    }

    private fun showResultDialog(file: File, autoShare: Boolean) {
        if (autoShare) { shareFile(file); return }
        AlertDialog.Builder(this)
            .setTitle("Sanitized copy ready")
            .setMessage("The exported JPEG was rebuilt from pixel data, so the original image metadata is not carried into this copy. Optional visual redactions were applied according to your toggles.")
            .setNegativeButton("Done", null)
            .setPositiveButton("Share sanitized copy") { _, _ -> shareFile(file) }
            .show()
    }

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Share sanitized image"))
    }

    private fun installClipboardGuard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener {
            clipboardClearRunnable?.let { handler.removeCallbacks(it) }
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            if (prefs.getBoolean("clipboard_warn", true) && looksClipboardSensitive(text)) {
                Toast.makeText(this, "Sensitive-looking text is on the clipboard", Toast.LENGTH_SHORT).show()
                addLog("Clipboard warning shown")
            }
            if (prefs.getBoolean("clipboard_clear", true)) {
                clipboardClearRunnable = Runnable {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                    addLog("Clipboard auto-cleared")
                }.also { handler.postDelayed(it, 30_000L) }
            }
        }
    }

    private fun looksClipboardSensitive(s: String): Boolean {
        if (s.length < 6) return false
        return looksSensitive(s) || Regex("(?i)(password|passwd|passcode|secret|token|api[_ -]?key|ssn|routing|account)").containsMatchIn(s)
    }

    private fun refreshPrivacyStatus() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val lp = active?.let { cm.getLinkProperties(it) }
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val privateDns = if (android.os.Build.VERSION.SDK_INT >= 28) !lp?.privateDnsServerName.isNullOrBlank() else false
        val dev = Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        val adb = Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        val accessibility = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').filter { it.isNotBlank() }.size
        val text = buildString {
            append("VPN                  ${if (vpn) "ACTIVE" else "Not active"}\n")
            append("Private DNS          ${if (privateDns) "Configured" else "Not detected"}\n")
            append("Current transport    ${if (wifi) "Wi-Fi" else if (caps != null) "Other network" else "Offline"}\n")
            append("Developer options    ${if (dev) "Enabled" else "Off"}\n")
            append("USB debugging        ${if (adb) "Enabled" else "Off"}\n")
            append("Accessibility        $accessibility enabled service${if (accessibility == 1) "" else "s"}")
        }
        val target = findTaggedText(window.decorView, "privacy_status")
        target?.text = text
        status.text = "Privacy status refreshed."
    }

    private fun findTaggedText(view: View, tag: String): TextView? {
        if (view is TextView && view.tag == tag) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findTaggedText(view.getChildAt(i), tag)
                if (found != null) return found
            }
        }
        return null
    }

    private fun confirmPanic() {
        AlertDialog.Builder(this)
            .setTitle("Panic cleanup")
            .setMessage("Clear the clipboard, temporary sanitized images and local activity log? Your toggle settings will remain unchanged.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clean now") { _, _ -> panicCleanup() }
            .show()
    }

    private fun panicCleanup() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        File(cacheDir, "sanitized").deleteRecursively()
        prefs.edit().remove("activity_log").apply()
        refreshLog()
        status.text = "Panic cleanup complete."
    }

    private fun addLog(message: String) {
        val stamp = SimpleDateFormat("MMM d • h:mm a", Locale.US).format(Date())
        val old = prefs.getString("activity_log", "").orEmpty().lines().filter { it.isNotBlank() }
        val lines = (listOf("$stamp  $message") + old).take(10)
        prefs.edit().putString("activity_log", lines.joinToString("\n")).apply()
        if (::activityLog.isInitialized) refreshLog()
    }

    private fun refreshLog() {
        val value = prefs.getString("activity_log", "").orEmpty()
        activityLog.text = if (value.isBlank()) "No local activity yet." else value
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundColor(0xFF111111.toInt())
    }

    private fun title(textValue: String) = TextView(this).apply {
        text = textValue
        setTextColor(Color.WHITE)
        textSize = 13f
        letterSpacing = 0.12f
        setTypeface(typeface, 1)
    }

    private fun primaryButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.BLACK)
        setBackgroundColor(Color.WHITE)
        setOnClickListener { action() }
    }

    private fun outlineButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFF222222.toInt())
        setOnClickListener { action() }
    }

    private fun marginTop(value: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(value) }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
