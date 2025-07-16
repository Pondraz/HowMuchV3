package com.example.howmuchv2

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.howmuchv2.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), ObjectDetectorHelper.DetectorListener, TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private lateinit var cameraExecutor: ExecutorService
    private var tts: TextToSpeech? = null

    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var isFlashOn: Boolean = false

    private var lastSpokenLabel: String? = null
    private var lastSpokenTime: Long = 0
    private var lastInferenceTimestamp: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this, this)
        setupModelSpinner()
        setupCameraButtons()
        setupConfidenceSlider()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun setupCameraButtons() {
        binding.flipCameraButton.setOnClickListener {
            lensFacing = if (CameraSelector.LENS_FACING_BACK == lensFacing)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }

        binding.flashButton.setOnClickListener { toggleFlash() }
    }

    private fun toggleFlash() {
        if (camera?.cameraInfo?.hasFlashUnit() == true) {
            isFlashOn = !isFlashOn
            camera?.cameraControl?.enableTorch(isFlashOn)
            binding.flashButton.setImageResource(
                if (isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
            )
        } else {
            Toast.makeText(this, "Flash tidak tersedia", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupModelSpinner() {
        val models = arrayOf("yolov12.tflite")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, models)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.modelSpinner.adapter = adapter

        binding.modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                val modelName = parent.getItemAtPosition(pos).toString()
                Log.d(TAG, "Selected model: $modelName")

                if (::objectDetectorHelper.isInitialized) {
                    objectDetectorHelper.clearObjectDetector()
                    objectDetectorHelper.setupObjectDetector(modelName)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun setupConfidenceSlider() {
        binding.confidenceSlider.addOnChangeListener { _, value, _ ->
            val threshold = value
            binding.thresholdValueTv.text = "%.2f".format(threshold)
            if (::objectDetectorHelper.isInitialized) {
                objectDetectorHelper.setThreshold(threshold)
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            cameraProvider?.unbindAll()

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            objectDetectorHelper = ObjectDetectorHelper(
                context = this,
                detectorListener = this,
                currentModel = binding.modelSpinner.selectedItem.toString()
            )

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        objectDetectorHelper.detect(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            try {
                camera = cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
                updateFlashButtonState()
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateFlashButtonState() {
        val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
        binding.flashButton.visibility = if (hasFlash) View.VISIBLE else View.GONE
        isFlashOn = false
        binding.flashButton.setImageResource(R.drawable.ic_flash_off)
    }

    override fun onResults(
        results: List<DetectionResult>?,
        inferenceTime: Long,
        imageHeight: Int,
        imageWidth: Int
    ) {
        runOnUiThread {
            // Display inference time
            binding.inferenceTimeTv.text = "Inference Time: $inferenceTime ms"

            // Calculate FPS
            val currentTime = System.currentTimeMillis()
            val fps = if (lastInferenceTimestamp != 0L) {
                (1000 / (currentTime - lastInferenceTimestamp)).coerceAtMost(60)
            } else 0
            lastInferenceTimestamp = currentTime

            binding.fpsTv.text = "FPS: $fps"

            // Draw detections
            binding.overlayView.setResults(results ?: listOf(), imageHeight, imageWidth)

            // Speak only the top result
            results?.firstOrNull()?.let { detection ->
                speakOut(detection.text)
            }
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("id", "ID"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "The Language specified is not supported!")
            }
        } else {
            Log.e(TAG, "TTS Initialization Failed!")
        }
    }

    private fun speakOut(label: String) {
        val currentTime = System.currentTimeMillis()
        if (label != lastSpokenLabel || (currentTime - lastSpokenTime > 3000)) {
            lastSpokenLabel = label
            lastSpokenTime = currentTime
            tts?.speak(label, TextToSpeech.QUEUE_FLUSH, null, "")
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        tts?.stop()
        tts?.shutdown()
    }

    companion object {
        private const val TAG = "RupiahDetector"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
