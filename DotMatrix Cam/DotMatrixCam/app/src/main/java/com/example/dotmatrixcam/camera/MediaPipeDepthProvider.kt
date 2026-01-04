package com.example.dotmatrixcam.camera

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter.ImageSegmenterOptions
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Optional

class MediaPipeDepthProvider : DepthDataProvider {

    companion object {
        private const val TAG = "MediaPipeDepth"
        private const val MODEL_NAME = "selfie_segmenter.tflite"
    }

    private var imageSegmenter: ImageSegmenter? = null
    private var depthTextureId: Int = -1
    private var maskWidth: Int = 0
    private var maskHeight: Int = 0

    @Volatile private var latestMaskByteBuffer: ByteBuffer? = null
    @Volatile private var isProcessing = false
    private var isInitialized = false

    override fun initialize(context: Context, width: Int, height: Int, cameraTextureId: Int) {
        if (isInitialized) return
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_NAME)
                .build()

            val options = ImageSegmenterOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setOutputConfidenceMasks(true)
                .setResultListener(this::processResult) // Correct: Use method reference
                .setErrorListener { error ->
                    Log.e(TAG, "MediaPipe Error: ${error.message}")
                    isProcessing = false
                }
                .build()

            imageSegmenter = ImageSegmenter.createFromOptions(context, options)
            createDepthTexture()
            isInitialized = true
            Log.d(TAG, "MediaPipe initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init MediaPipe: ${e.message}", e)
        }
    }

    override fun resume() { /* No-op for MediaPipe */ }
    override fun pause() { /* No-op for MediaPipe */ }

    private fun createDepthTexture() {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        depthTextureId = textures[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, 1, 1, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null)
    }

    private fun processResult(result: ImageSegmenterResult, inputImage: MPImage) {
        try {
            result.confidenceMasks().ifPresent { masks ->
                if (masks.isNotEmpty()) {
                    masks[0].use { mask -> // .use ensures the MPImage is closed
                        maskWidth = mask.width
                        maskHeight = mask.height
                        val maskBuffer = mask.planes[0].buffer.asFloatBuffer()

                        val byteBuffer = ByteBuffer.allocateDirect(maskWidth * maskHeight).order(ByteOrder.nativeOrder())

                        while (maskBuffer.hasRemaining()) {
                            val pixelValue = maskBuffer.get()
                            val byteVal = (pixelValue * 255f).toInt().coerceIn(0, 255).toByte()
                            byteBuffer.put(byteVal)
                        }
                        byteBuffer.flip()

                        synchronized(this) {
                            latestMaskByteBuffer = byteBuffer
                        }
                    }
                }
            }
        } catch(e: Exception) {
            Log.e(TAG, "Error processing MediaPipe result", e)
        } finally {
            isProcessing = false
        }
    }

    fun sendFrame(bitmap: Bitmap) {
        if (isProcessing || imageSegmenter == null || !isInitialized) return

        isProcessing = true
        try {
            BitmapImageBuilder(bitmap).build().use { mpImage -> // .use ensures the image is closed
                imageSegmenter?.segmentAsync(mpImage, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to segment frame: ${e.message}")
            isProcessing = false
        }
    }

    override fun update() {
        synchronized(this) {
            latestMaskByteBuffer?.let { buffer ->
                if (buffer.hasRemaining() && maskWidth > 0 && maskHeight > 0) {
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTextureId)
                    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, maskWidth, maskHeight, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buffer)
                    latestMaskByteBuffer = null
                }
            }
        }
    }

    override fun getDepthTextureId(): Int = depthTextureId

    override fun release() {
        imageSegmenter?.close()
        imageSegmenter = null
        if (depthTextureId != -1) {
            GLES30.glDeleteTextures(1, intArrayOf(depthTextureId), 0)
            depthTextureId = -1
        }
        isInitialized = false
        Log.d(TAG, "MediaPipe released")
    }
}
