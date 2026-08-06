package online.pcguys.objectrecognizer

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var resultText: TextView
    private lateinit var feedbackRow: LinearLayout
    private lateinit var identifyButton: Button
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
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
            text = "PCG OBJECT RECOGNIZER\nCrosshair selects the object. Only the box is analyzed."
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(20), dp(18), dp(20))
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

        identifyButton = Button(this).apply {
            text = "IDENTIFY OBJECT"
            textSize = 18f
            setOnClickListener { captureTarget() }
        }
        bottom.addView(resultText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bottom.addView(feedbackRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bottom.addView(identifyButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                resultText.text = "Unable to start camera: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureTarget() {
        val capture = imageCapture ?: return
        feedbackRow.visibility = View.GONE
        identifyButton.isEnabled = false
        resultText.text = "Capturing only the target area…"
        val file = File.createTempFile("pcg_target_", ".jpg", cacheDir)
        val output = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(output, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                try {
                    val full = BitmapFactory.decodeFile(file.absolutePath)
                    val roi = cropTargetRegion(full)
                    isolateCrosshairObject(roi)
                } catch (e: Exception) {
                    runOnUiThread {
                        resultText.text = "Could not analyze image. Try again."
                        identifyButton.isEnabled = true
                    }
                } finally {
                    file.delete()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    resultText.text = "Capture failed. Try again."
                    identifyButton.isEnabled = true
                }
                file.delete()
            }
        })
    }

    private fun cropTargetRegion(bitmap: Bitmap): Bitmap {
        val left = (bitmap.width * 0.14f).toInt().coerceAtLeast(0)
        val top = (bitmap.height * 0.22f).toInt().coerceAtLeast(0)
        val width = (bitmap.width * 0.72f).toInt().coerceAtMost(bitmap.width - left)
        val height = (bitmap.height * 0.38f).toInt().coerceAtMost(bitmap.height - top)
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    private fun isolateCrosshairObject(roi: Bitmap) {
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
        val detector = ObjectDetection.getClient(options)
        detector.process(InputImage.fromBitmap(roi, 0))
            .addOnSuccessListener { objects ->
                val cx = roi.width / 2f
                val cy = roi.height / 2f
                val selected = objects
                    .filter { it.boundingBox.contains(cx.toInt(), cy.toInt()) }
                    .minByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    ?: objects.minByOrNull {
                        val bx = it.boundingBox.exactCenterX()
                        val by = it.boundingBox.exactCenterY()
                        ((bx - cx) * (bx - cx) + (by - cy) * (by - cy)).toInt()
                    }

                val focused = if (selected != null) {
                    val box = selected.boundingBox
                    val padX = (box.width() * 0.12f).toInt()
                    val padY = (box.height() * 0.12f).toInt()
                    val left = (box.left - padX).coerceAtLeast(0)
                    val top = (box.top - padY).coerceAtLeast(0)
                    val right = (box.right + padX).coerceAtMost(roi.width)
                    val bottom = (box.bottom + padY).coerceAtMost(roi.height)
                    val areaRatio = ((right - left) * (bottom - top)).toFloat() / (roi.width * roi.height).toFloat()
                    if (right > left && bottom > top && areaRatio < 0.92f) {
                        Bitmap.createBitmap(roi, left, top, right - left, bottom - top)
                    } else roi
                } else roi
                analyzeFocusedBitmap(focused)
            }
            .addOnFailureListener { analyzeFocusedBitmap(roi) }
    }

    private fun analyzeFocusedBitmap(bitmap: Bitmap) {
        val input = InputImage.fromBitmap(bitmap, 0)
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.18f).build())
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        labeler.process(input)
            .addOnSuccessListener { labels ->
                recognizer.process(input)
                    .addOnSuccessListener { text -> showResult(labels, text.text) }
                    .addOnFailureListener { showResult(labels, "") }
            }
            .addOnFailureListener {
                runOnUiThread {
                    resultText.text = "Recognition failed. Fill the target with one object and try again."
                    identifyButton.isEnabled = true
                }
            }
    }

    private fun showResult(labels: List<ImageLabel>, detectedText: String) {
        val ignored = setOf("hand", "finger", "person", "skin", "arm", "gesture", "human body", "room", "indoor")
        val filtered = labels
            .filterNot { ignored.contains(it.text.lowercase(Locale.US)) }
            .sortedByDescending { it.confidence }
            .take(8)
        val fallback = labels.sortedByDescending { it.confidence }.take(8)
        val candidates = if (filtered.isNotEmpty()) filtered else fallback
        val words = detectedText
            .replace("\n", " ")
            .split(Regex("\\s+"))
            .map { it.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9-]"), "") }
            .filter { it.length >= 2 }
            .distinct()
            .take(12)
        currentSignature = buildSignature(candidates, words)
        val learned = getSharedPreferences("recognizer_learning", MODE_PRIVATE).getString("correction_$currentSignature", null)
        currentPrediction = learned ?: candidates.firstOrNull()?.text.orEmpty()

        runOnUiThread {
            resultText.text = if (currentPrediction.isBlank()) {
                "Not sure what this is. Move closer and keep the object centered on the crosshair."
            } else {
                buildString {
                    append(if (learned != null) "Learned identification: " else "Likely: ")
                    append(currentPrediction)
                    candidates.firstOrNull()?.let { append("\nConfidence: ${(it.confidence * 100).toInt()}%") }
                    if (words.isNotEmpty()) append("\nVisible text: ${words.joinToString(", ")}")
                    val alternatives = candidates.map { it.text }.filterNot { it.equals(currentPrediction, true) }.distinct().take(5)
                    if (alternatives.isNotEmpty()) append("\nOther matches: ${alternatives.joinToString(", ")}")
                }
            }
            feedbackRow.visibility = if (currentPrediction.isBlank()) View.GONE else View.VISIBLE
            identifyButton.isEnabled = true
        }
    }

    private fun buildSignature(labels: List<ImageLabel>, words: List<String>): String {
        val labelPart = labels.take(5).joinToString("|") { it.text.lowercase(Locale.US) }
        val textPart = words.take(8).joinToString("|")
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
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dp(3).toFloat()
        }
        private val shadePaint = Paint().apply { color = 0x77000000 }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width * 0.72f
            val h = height * 0.38f
            val left = (width - w) / 2f
            val top = height * 0.22f
            val right = left + w
            val bottom = top + h
            canvas.drawRect(0f, 0f, width.toFloat(), top, shadePaint)
            canvas.drawRect(0f, bottom, width.toFloat(), height.toFloat(), shadePaint)
            canvas.drawRect(0f, top, left, bottom, shadePaint)
            canvas.drawRect(right, top, width.toFloat(), bottom, shadePaint)
            canvas.drawRoundRect(left, top, right, bottom, dp(18).toFloat(), dp(18).toFloat(), borderPaint)
            canvas.drawLine(width / 2f - dp(18), top + h / 2f, width / 2f + dp(18), top + h / 2f, borderPaint)
            canvas.drawLine(width / 2f, top + h / 2f - dp(18), width / 2f, top + h / 2f + dp(18), borderPaint)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
