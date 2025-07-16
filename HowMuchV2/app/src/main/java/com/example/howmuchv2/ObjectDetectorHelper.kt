package com.example.howmuchv2 // Pastikan ini sesuai dengan package Anda

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.Rot90Op
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

class ObjectDetectorHelper(
    val context: Context,
    val detectorListener: DetectorListener,
    var currentModel: String
) {
    private var interpreter: Interpreter? = null
    private var labels = listOf<String>()

    private var tensorWidth = 0
    private var tensorHeight = 0

    var confidenceThreshold = 0.5f
    var iouThreshold = 0.5f

    init {
        setupObjectDetector(currentModel)
    }

    fun setupObjectDetector(modelName: String) {
        try {
            val modelFilename = modelName
            val assetFileDescriptor = context.assets.openFd(modelFilename)
            val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = fileInputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            val options = Interpreter.Options()
            options.setNumThreads(4)
            interpreter = Interpreter(modelBuffer, options)

            val inputTensor = interpreter!!.getInputTensor(0)
            tensorWidth = inputTensor.shape()[1]
            tensorHeight = inputTensor.shape()[2]

            labels = context.assets.open("labels.txt").bufferedReader().readLines()
            Log.d("ObjectDetectorHelper", "Model $modelName loaded successfully.")
        } catch (e: Exception) {
            detectorListener.onError("Failed to initialize TFLite interpreter: ${e.message}")
            Log.e("ObjectDetectorHelper", "Error initializing interpreter", e)
        }
    }

    fun detect(imageProxy: ImageProxy) {
        if (interpreter == null) {
            imageProxy.close()
            return
        }

        val frameTime = SystemClock.uptimeMillis()

        val bitmap = imageProxy.toBitmap()
        val imageProcessor = ImageProcessor.Builder()
            .add(Rot90Op(-imageProxy.imageInfo.rotationDegrees / 90))
            .build()
        val rotatedBitmap = imageProcessor.process(TensorImage.fromBitmap(bitmap)).bitmap

        val results = detect(rotatedBitmap)
        val inferenceTime = SystemClock.uptimeMillis() - frameTime

        detectorListener.onResults(
            results,
            inferenceTime,
            rotatedBitmap.height,
            rotatedBitmap.width
        )
        imageProxy.close()
    }

    private fun detect(frame: Bitmap): List<DetectionResult> {
        interpreter ?: return emptyList()

        val resizedBitmap = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, true)
        val byteBuffer = convertBitmapToByteBuffer(resizedBitmap)

        val outputShape = interpreter!!.getOutputTensor(0).shape()
        val outputBuffer = ByteBuffer.allocateDirect(outputShape[0] * outputShape[1] * outputShape[2] * 4)
        outputBuffer.order(ByteOrder.nativeOrder())

        interpreter?.run(byteBuffer, outputBuffer)

        return processOutput(outputBuffer, frame.width, frame.height)
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(1 * tensorWidth * tensorHeight * 3 * 4)
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(tensorWidth * tensorHeight)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until tensorHeight) {
            for (j in 0 until tensorWidth) {
                val `val` = intValues[pixel++]
                byteBuffer.putFloat(((`val` shr 16) and 0xFF) / 255.0f)
                byteBuffer.putFloat(((`val` shr 8) and 0xFF) / 255.0f)
                byteBuffer.putFloat((`val` and 0xFF) / 255.0f)
            }
        }
        return byteBuffer
    }

    private fun processOutput(buffer: ByteBuffer, imageWidth: Int, imageHeight: Int): List<DetectionResult> {
        buffer.rewind()
        val outputShape = interpreter!!.getOutputTensor(0).shape()
        val numClasses = outputShape[1] - 4
        val numDetections = outputShape[2]

        // Transpose buffer
        val transposedBuffer = Array(numDetections) { FloatArray(numClasses + 4) }
        for (i in 0 until numDetections) {
            for (j in 0 until numClasses + 4) {
                transposedBuffer[i][j] = buffer.getFloat((j * numDetections + i) * 4)
            }
        }

        val detections = mutableListOf<DetectionResult>()

        for (i in 0 until numDetections) {
            val detection = transposedBuffer[i]
            val scores = detection.slice(4 until numClasses + 4)
            val maxScore = scores.maxOrNull() ?: 0f

            if (maxScore > confidenceThreshold) {
                val bestClassIndex = scores.indexOf(maxScore)

                // --- PERBAIKAN KRUSIAL BERDASARKAN KODE PYTHON ---
                // Asumsikan output cx,cy,w,h adalah TERNORMALISASI (0.0 - 1.0)
                val cx_norm = detection[0]
                val cy_norm = detection[1]
                val w_norm = detection[2]
                val h_norm = detection[3]

                // Ubah ke koordinat piksel dalam sistem gambar asli
                val left = (cx_norm - w_norm / 2) * imageWidth
                val top = (cy_norm - h_norm / 2) * imageHeight
                val right = (cx_norm + w_norm / 2) * imageWidth
                val bottom = (cy_norm + h_norm / 2) * imageHeight

                val boundingBox = RectF(left, top, right, bottom)
                val text = labels[bestClassIndex]
                detections.add(DetectionResult(boundingBox, text, maxScore))
            }
        }
        return applyNMS(detections)
    }

    private fun applyNMS(detections: List<DetectionResult>): List<DetectionResult> {
        val sortedDetections = detections.sortedByDescending { it.confidence }
        val selectedDetections = mutableListOf<DetectionResult>()
        val active = BooleanArray(sortedDetections.size) { true }
        var numActive = active.size

        for (i in sortedDetections.indices) {
            if (active[i]) {
                val boxA = sortedDetections[i]
                selectedDetections.add(boxA)
                if (numActive == 1) break

                for (j in (i + 1) until sortedDetections.size) {
                    if (active[j]) {
                        val boxB = sortedDetections[j]
                        if (calculateIoU(boxA.boundingBox, boxB.boundingBox) > iouThreshold) {
                            active[j] = false
                            numActive--
                            if (numActive == 0) break
                        }
                    }
                }
                if (numActive == 0) break
            }
        }
        return selectedDetections
    }

    private fun calculateIoU(boxA: RectF, boxB: RectF): Float {
        val xA = max(boxA.left, boxB.left)
        val yA = max(boxA.top, boxB.top)
        val xB = min(boxA.right, boxB.right)
        val yB = min(boxA.bottom, boxB.bottom)
        val intersectionArea = max(0f, xB - xA) * max(0f, yB - yA)
        val boxAArea = (boxA.right - boxA.left) * (boxA.bottom - boxA.top)
        val boxBArea = (boxB.right - boxB.left) * (boxB.bottom - boxB.top)
        val unionArea = boxAArea + boxBArea - intersectionArea
        return if (unionArea <= 0) 0f else intersectionArea / unionArea
    }

    fun setThreshold(threshold: Float) {
        confidenceThreshold = threshold
    }


    fun clearObjectDetector() {
        interpreter?.close()
        interpreter = null
    }

    interface DetectorListener {
        fun onError(error: String)
        fun onResults(
            results: List<DetectionResult>?,
            inferenceTime: Long,
            imageHeight: Int,
            imageWidth: Int
        )
    }
}
