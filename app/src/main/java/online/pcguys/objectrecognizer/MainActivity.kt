package online.pcguys.objectrecognizer

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var resultText: TextView
    private lateinit var feedbackRow: LinearLayout
    private lateinit var cameraExecutor: ExecutorService
    private val scanRequested = AtomicBoolean(false)
    private var currentSignature = ""
    private var currentPrediction = ""

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else resultText.text = "Camera permission is required."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(TargetView(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val top = TextView(this).apply {
            text = "PCG OBJECT RECOGNIZER\nPlace one object inside the target"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(0xAA000000.toInt())
        }
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(16), dp(22), dp(24))
            setBackgroundColor(0xE6000000.toInt())
        }
        resultText = TextView(this).apply {
            text = "Ready to identify."
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(12))
        }
        feedbackRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val yes = Button(this).apply {
            text = "👍 CORRECT"
            setOnClickListener { savePositiveFeedback() }
        }
        val no = Button(this).apply {
            text = "👎 WRONG"
            setOnClickListener { requestCorrection() }
        }
        feedbackRow.addView(yes, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        feedbackRow.addView(no, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })

        val identify = Button(this).apply {
            text = "IDENTIFY OBJECT"
            textSize = 18f
            setOnClickListener {
                feedbackRow.visibility = View.GONE
                resultText.text = "Analyzing shape, labels and visible text…"
                scanRequested.set(true)
            }
        }
        bottom.addView(resultText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bottom.addView(feedbackRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bottom.addView(identify, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor) { image -> analyze(image) } }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                resultText.text = "Unable to start camera: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        if (!scanRequested.compareAndSet(true, false)) {
            proxy.close()
            return
        }
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.25f).build())
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        labeler.process(input)
            .addOnSuccessListener { labels ->
                recognizer.process(input)
                    .addOnSuccessListener { text -> showResult(labels, text.text) }
                    .addOnFailureListener { showResult(labels, "") }
                    .addOnCompleteListener { proxy.close() }
            }
            .addOnFailureListener {
                resultText.text = "Recognition failed. Hold the object inside the target and try again."
                proxy.close()
            }
    }

    private fun showResult(labels: List<ImageLabel>, detectedText: String) {
        val filtered = labels
            .filterNot { it.text.equals("hand", true) || it.text.equals("finger", true) || it.text.equals("person", true) }
            .sortedByDescending { it.confidence }
            .take(6)
        val fallback = labels.sortedByDescending { it.confidence }.take(6)
        val candidates = if (filtered.isNotEmpty()) filtered else fallback
        val words = detectedText
            .replace("\n", " ")
            .split(Regex("\\s+"))
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.length >= 3 }
            .distinct()
            .take(8)
        currentSignature = buildSignature(candidates, words)
        val learned = getSharedPreferences("recognizer_learning", MODE_PRIVATE).getString("correction_$currentSignature", null)
        currentPrediction = learned ?: candidates.firstOrNull()?.text.orEmpty()

        resultText.text = if (currentPrediction.isBlank()) {
            "Not sure what this is. Move closer, fill the target and try again."
        } else {
            buildString {
                append(if (learned != null) "Learned identification: " else "Likely: ")
                append(currentPrediction)
                candidates.firstOrNull()?.let { append("\nConfidence: ${(it.confidence * 100).toInt()}%") }
                if (words.isNotEmpty()) append("\nVisible text: ${words.joinToString(", ")}")
                val alternatives = candidates.map { it.text }.filterNot { it.equals(currentPrediction, true) }.distinct().take(4)
                if (alternatives.isNotEmpty()) append("\nOther matches: ${alternatives.joinToString(", ")}")
            }
        }
        feedbackRow.visibility = if (currentPrediction.isBlank()) View.GONE else View.VISIBLE
    }

    private fun buildSignature(labels: List<ImageLabel>, words: List<String>): String {
        val labelPart = labels.take(4).joinToString("|") { it.text.lowercase(Locale.US) }
        val textPart = words.take(5).joinToString("|")
        return "$labelPart::$textPart".hashCode().toString()
    }

    private fun savePositiveFeedback() {
        val prefs = getSharedPreferences("recognizer_learning", MODE_PRIVATE)
        val key = "positive_$currentSignature"
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
        resultText.append("\n✓ Saved as correct. This device will favor this result.")
        feedbackRow.visibility = View.GONE
    }

    private fun requestCorrection() {
        val input = EditText(this).apply {
            hint = "Correct object name"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("What is this object?")
            .setMessage("Enter the correct identification. It will be remembered on this device for similar future scans.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val correction = input.text.toString().trim()
                if (correction.isNotEmpty()) {
                    getSharedPreferences("recognizer_learning", MODE_PRIVATE)
                        .edit()
                        .putString("correction_$currentSignature", correction)
                        .apply()
                    currentPrediction = correction
                    resultText.text = "Learned identification: $correction\nSaved on this device."
                    feedbackRow.visibility = View.GONE
                }
            }
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class TargetView : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dp(3).toFloat()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width * 0.72f
            val h = height * 0.38f
            val left = (width - w) / 2f
            val top = height * 0.22f
            canvas.drawRoundRect(left, top, left + w, top + h, dp(18).toFloat(), dp(18).toFloat(), paint)
            canvas.drawLine(width / 2f - dp(18), top + h / 2f, width / 2f + dp(18), top + h / 2f, paint)
            canvas.drawLine(width / 2f, top + h / 2f - dp(18), width / 2f, top + h / 2f + dp(18), paint)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
