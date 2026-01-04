package com.example.dotmatrixcam.camera

import android.app.Activity
import android.content.Context
import android.opengl.GLES30
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.FloatBuffer

class ArCoreDepthProvider : DepthDataProvider {

    private var session: Session? = null
    private var depthTextureId: Int = -1
    private var width: Int = 0
    private var height: Int = 0
    private var isInitialized = false
    private var hasResumed = false

    private var floatBuffer: FloatBuffer? = null

    override fun initialize(context: Context, width: Int, height: Int, cameraTextureId: Int) {
        if (isInitialized) return
        this.width = width
        this.height = height

        try {
            session = Session(context as Activity)
            val config = Config(session)
            config.depthMode = Config.DepthMode.AUTOMATIC
            config.focusMode = Config.FocusMode.AUTO
            session?.configure(config)

            // Tell ARCore where to render the camera feed.
            session?.setCameraTextureName(cameraTextureId)

            createDepthTexture()
            isInitialized = true
            Log.d("ArCoreDepthProvider", "ARCore session configured.")

        } catch (e: Exception) {
            Log.e("ArCoreDepthProvider", "Failed to initialize ARCore: ${e.message}", e)
            isInitialized = false
            release()
        }
    }

    override fun resume() {
        if (isInitialized && !hasResumed) {
            try {
                session?.resume()
                hasResumed = true
                Log.d("ArCoreDepthProvider", "ARCore session resumed.")
            } catch (e: Exception) {
                Log.e("ArCoreDepthProvider", "Failed to resume ARCore session", e)
            }
        }
    }

    override fun pause() {
        if (isInitialized && hasResumed) {
            session?.pause()
            hasResumed = false
            Log.d("ArCoreDepthProvider", "ARCore session paused.")
        }
    }

    private fun createDepthTexture() {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        depthTextureId = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, width, height, 0, GLES30.GL_RED, GLES30.GL_FLOAT, null)
    }

    override fun update() {
        if (!isInitialized || !hasResumed) return

        try {
            session?.setDisplayGeometry(0, width, height)
            val frame = session?.update()
            val depthImage = frame?.acquireDepthImage16Bits()

            if (depthImage != null) {
                val depthWidth = depthImage.width
                val depthHeight = depthImage.height
                val buffer = depthImage.planes[0].buffer.asShortBuffer()

                if (floatBuffer == null || floatBuffer!!.capacity() < buffer.remaining()) {
                    floatBuffer = FloatBuffer.allocate(buffer.remaining())
                }
                floatBuffer!!.clear()

                val maxDepthMm = 8000.0f
                while (buffer.hasRemaining()) {
                    val depthShort = buffer.get().toUShort().toFloat()
                    val normalizedDepth = (depthShort / maxDepthMm).coerceIn(0.0f, 1.0f)
                    floatBuffer!!.put(normalizedDepth)
                }

                floatBuffer!!.flip()

                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTextureId)
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, depthWidth, depthHeight, GLES30.GL_RED, GLES30.GL_FLOAT, floatBuffer)

                depthImage.close()
            }
        } catch (e: Exception) {
            if (e !is NotYetAvailableException) {
                 Log.e("ArCoreDepthProvider", "Error updating ARCore frame", e)
            }
        }
    }

    override fun getDepthTextureId(): Int {
        return depthTextureId
    }

    override fun release() {
        if (session != null) {
            if (hasResumed) session?.pause()
            session?.close()
            session = null
        }
        if (depthTextureId != -1) {
            val textures = intArrayOf(depthTextureId)
            GLES30.glDeleteTextures(1, textures, 0)
            depthTextureId = -1
        }
        isInitialized = false
        hasResumed = false
        Log.d("ArCoreDepthProvider", "ARCore session released.")
    }
}
