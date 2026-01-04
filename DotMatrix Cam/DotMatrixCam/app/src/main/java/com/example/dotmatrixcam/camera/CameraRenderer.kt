package com.example.dotmatrixcam.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.example.dotmatrixcam.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class CameraRenderer(
    private val context: Context,
    val depthProvider: DepthDataProvider, // Accept the provider directly
    private val onSurfaceReady: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    @Volatile var density: Float = 50f
    @Volatile var dotSizeFactor: Float = 1.0f
    @Volatile var distortionFactor: Float = 0.5f
    @Volatile var foregroundColor: FloatArray = floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f)
    @Volatile var backgroundColor: FloatArray = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)

    private var surfaceTexture: SurfaceTexture? = null
    private var cameraTextureId: Int = -1
    private var depthTextureId: Int = -1
    private var programId: Int = 0
    private val transformMatrix = FloatArray(16)

    @Volatile private var captureRequest = false
    private var captureCallback: ((Bitmap) -> Unit)? = null
    private var viewWidth = 0
    private var viewHeight = 0

    private val vertexBuffer: FloatBuffer
    private val textureBuffer: FloatBuffer
    @Volatile private var updateSurface = false
    private var glSurfaceView: GLSurfaceView? = null

    init {
        val squareCoords = floatArrayOf(-1.0f, 1.0f, -1.0f, -1.0f, 1.0f, -1.0f, 1.0f, 1.0f)
        val textureCoords = floatArrayOf(0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f)

        vertexBuffer = ByteBuffer.allocateDirect(squareCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(squareCoords)
        vertexBuffer.position(0)
        textureBuffer = ByteBuffer.allocateDirect(textureCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(textureCoords)
        textureBuffer.position(0)
    }

    fun attachGLSurfaceView(view: GLSurfaceView) {
        this.glSurfaceView = view
        view.setEGLContextClientVersion(3)
        view.setRenderer(this)
        view.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    }

    fun resume() {
        depthProvider.resume()
        glSurfaceView?.onResume()
    }

    fun pause() {
        depthProvider.pause()
        glSurfaceView?.onPause()
    }

    fun release() {
        depthProvider.release()
    }

    fun captureFrame(callback: (Bitmap) -> Unit) {
        captureCallback = callback
        captureRequest = true
        glSurfaceView?.requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        cameraTextureId = createOESTexture()
        surfaceTexture = SurfaceTexture(cameraTextureId)
        surfaceTexture?.setOnFrameAvailableListener(this)
        onSurfaceReady(surfaceTexture!!)
        setupShaders()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height

        // The provider is already created, just initialize it with surface dimensions.
        depthProvider.initialize(context, width, height, cameraTextureId)
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(this) {
            if (updateSurface) {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(transformMatrix)
                updateSurface = false
            }
        }

        depthProvider.update()
        depthTextureId = depthProvider.getDepthTextureId()

        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(programId)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTextureRGB"), 0)

        if (depthTextureId > 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTextureId)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTextureDepth"), 1)
        }

        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(programId, "uTextureMatrix"), 1, false, transformMatrix, 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uResolution"), viewWidth.toFloat(), viewHeight.toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uDensity"), density)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uDotSizeFactor"), dotSizeFactor)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uDistortionFactor"), distortionFactor)
        GLES30.glUniform4fv(GLES30.glGetUniformLocation(programId, "uForegroundColor"), 1, foregroundColor, 0)
        GLES30.glUniform4fv(GLES30.glGetUniformLocation(programId, "uBackgroundColor"), 1, backgroundColor, 0)

        val aPosition = GLES30.glGetAttribLocation(programId, "aPosition")
        val aTexCoord = GLES30.glGetAttribLocation(programId, "aTexCoord")
        GLES30.glEnableVertexAttribArray(aPosition)
        GLES30.glVertexAttribPointer(aPosition, 2, GLES30.GL_FLOAT, false, 8, vertexBuffer)
        GLES30.glEnableVertexAttribArray(aTexCoord)
        GLES30.glVertexAttribPointer(aTexCoord, 2, GLES30.GL_FLOAT, false, 8, textureBuffer)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, 4)
        GLES30.glDisableVertexAttribArray(aPosition)
        GLES30.glDisableVertexAttribArray(aTexCoord)

        if (captureRequest) {
            captureRequest = false
            handleCapture()
        }
    }

    private fun handleCapture() {
        val buffer = ByteBuffer.allocate(viewWidth * viewHeight * 4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        GLES30.glReadPixels(0, 0, viewWidth, viewHeight, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
        buffer.rewind()
        val bitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        val matrix = android.graphics.Matrix()
        matrix.preScale(1.0f, -1.0f)
        val flippedBitmap = Bitmap.createBitmap(bitmap, 0, 0, viewWidth, viewHeight, matrix, false)
        bitmap.recycle()
        val finalCallback = captureCallback
        captureCallback = null
        glSurfaceView?.post { finalCallback?.invoke(flippedBitmap) }
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        updateSurface = true
        glSurfaceView?.requestRender()
    }

    private fun createOESTexture(): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES30.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR.toFloat())
        GLES30.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR.toFloat())
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return textures[0]
    }

    private fun setupShaders() {
        val vertexShaderCode = """#version 300 es
            in vec4 aPosition;
            in vec4 aTexCoord;
            uniform mat4 uTextureMatrix;
            out vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTextureMatrix * aTexCoord).xy;
            }
        """.trimIndent()

        val fragmentShaderCode = try {
            context.resources.openRawResource(R.raw.dot_matrix_shader).bufferedReader().use { it.readText() }
        } catch (e: Exception) { "" }

        val vertexShader = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER).also { GLES30.glShaderSource(it, vertexShaderCode); GLES30.glCompileShader(it) }
        val fragmentShader = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER).also { GLES30.glShaderSource(it, fragmentShaderCode); GLES30.glCompileShader(it) }
        programId = GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, vertexShader)
            GLES30.glAttachShader(it, fragmentShader)
            GLES30.glLinkProgram(it)
        }
    }
}
