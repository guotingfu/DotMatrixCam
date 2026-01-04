package com.example.dotmatrixcam.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.example.dotmatrixcam.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class CameraRenderer(
    private val context: Context,
    val depthProvider: DepthDataProvider,
    private val onSurfaceReady: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private val TAG = "CameraRenderer"

    @Volatile var density: Float = 50f
    @Volatile var dotSizeFactor: Float = 1.0f
    @Volatile var distortionFactor: Float = 0.5f
    @Volatile var foregroundColor: FloatArray = floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f)
    @Volatile var backgroundColor: FloatArray = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)

    private var surfaceTexture: SurfaceTexture? = null
    private var cameraTextureId: Int = -1
    private var depthTextureId: Int = -1
    private var programId: Int = 0

    // 初始化为单位矩阵
    private val transformMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    @Volatile private var captureRequest = false
    private var captureCallback: ((Bitmap) -> Unit)? = null
    private var viewWidth = 0
    private var viewHeight = 0

    private val vertexBuffer: FloatBuffer
    private val textureBuffer: FloatBuffer
    @Volatile private var updateSurface = false
    private var glSurfaceView: GLSurfaceView? = null

    private var isArCore = false
    private var hasReceivedFirstFrame = false

    init {
        val squareCoords = floatArrayOf(-1.0f, 1.0f, -1.0f, -1.0f, 1.0f, -1.0f, 1.0f, 1.0f)
        val textureCoords = floatArrayOf(0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f)

        vertexBuffer = ByteBuffer.allocateDirect(squareCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(squareCoords)
        vertexBuffer.position(0)
        textureBuffer = ByteBuffer.allocateDirect(textureCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(textureCoords)
        textureBuffer.position(0)

        isArCore = depthProvider is ArCoreDepthProvider
    }

    fun attachGLSurfaceView(view: GLSurfaceView) {
        this.glSurfaceView = view
        view.setEGLContextClientVersion(3)
        view.setRenderer(this)
        // ARCore 需要连续渲染，MediaPipe 按需渲染
        view.renderMode = if (isArCore) GLSurfaceView.RENDERMODE_CONTINUOUSLY else GLSurfaceView.RENDERMODE_WHEN_DIRTY
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
        if (!isArCore) glSurfaceView?.requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        cameraTextureId = createOESTexture()
        surfaceTexture = SurfaceTexture(cameraTextureId)

        if (!isArCore) {
            surfaceTexture?.setOnFrameAvailableListener(this)
        }

        onSurfaceReady(surfaceTexture!!)
        setupShaders()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height
        depthProvider.initialize(context, width, height, cameraTextureId)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        if (programId == 0 || viewWidth == 0 || viewHeight == 0) return

        // --- 纹理更新逻辑 ---
        if (isArCore) {
            try {
                depthProvider.update()
                // 尝试获取矩阵，ARCore 通常会更新纹理
                surfaceTexture?.getTransformMatrix(transformMatrix)
                hasReceivedFirstFrame = true
            } catch (e: Exception) {
                Log.e(TAG, "ARCore update error", e)
            }
        } else {
            // MediaPipe 模式
            synchronized(this) {
                if (updateSurface) {
                    surfaceTexture?.updateTexImage()
                    surfaceTexture?.getTransformMatrix(transformMatrix)
                    updateSurface = false
                    hasReceivedFirstFrame = true
                }
            }
            // 没画面时等待，避免黑屏采样导致的闪烁
            if (!hasReceivedFirstFrame) return
        }

        depthTextureId = depthProvider.getDepthTextureId()

        GLES30.glUseProgram(programId)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTextureRGB"), 0)

        if (depthTextureId > 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTextureId)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTextureDepth"), 1)
        }

        // 传递矩阵给 Vertex Shader 使用 (注意：Shader 中去掉了对 uTextureMatrix 的引用，这里传递不影响，但为了保持 Vertex Shader 逻辑正确，必须传递)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(programId, "uTextureMatrix"), 1, false, transformMatrix, 0)

        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "u_resolution"), viewWidth.toFloat(), viewHeight.toFloat())

        val safeDensity = if (density < 1.0f) 50.0f else density
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "u_density"), safeDensity)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "u_dotSizeFactor"), dotSizeFactor)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "u_distortionFactor"), distortionFactor)
        GLES30.glUniform4fv(GLES30.glGetUniformLocation(programId, "u_foregroundColor"), 1, foregroundColor, 0)
        GLES30.glUniform4fv(GLES30.glGetUniformLocation(programId, "u_backgroundColor"), 1, backgroundColor, 0)

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
        // --- 关键修改：Vertex Shader 中恢复矩阵变换 ---
        val vertexShaderCode = """#version 300 es
            in vec4 aPosition;
            in vec4 aTexCoord;
            uniform mat4 uTextureMatrix; // 接收矩阵
            out vec2 vTexCoord;          // 输出变换后的坐标
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTextureMatrix * aTexCoord).xy; // 应用变换
            }
        """.trimIndent()

        val fragmentShaderCode = try {
            context.resources.openRawResource(R.raw.dot_matrix_shader).bufferedReader().use { it.readText() }
        } catch (e: Exception) { "" }

        if (fragmentShaderCode.isEmpty()) return

        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderCode)

        programId = GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, vertexShader)
            GLES30.glAttachShader(it, fragmentShader)
            GLES30.glLinkProgram(it)

            val linkStatus = IntArray(1)
            GLES30.glGetProgramiv(it, GLES30.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                Log.e(TAG, "Link Error: " + GLES30.glGetProgramInfoLog(it))
                GLES30.glDeleteProgram(it)
                programId = 0
            }
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES30.glCreateShader(type).also { shader ->
            GLES30.glShaderSource(shader, shaderCode)
            GLES30.glCompileShader(shader)

            val compileStatus = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                Log.e(TAG, "Compile Error ($type): " + GLES30.glGetShaderInfoLog(shader))
                GLES30.glDeleteShader(shader)
            }
        }
    }
}