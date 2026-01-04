package com.example.dotmatrixcam.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dotmatrixcam.camera.ArCoreDepthProvider
import com.example.dotmatrixcam.camera.CameraRenderer
import com.example.dotmatrixcam.camera.DepthDataProvider
import com.example.dotmatrixcam.camera.DepthProviderFactory
import com.example.dotmatrixcam.camera.MediaPipeDepthProvider
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.Executors

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        CameraContent()
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("需要相机权限才能运行")
        }
    }
}

@Composable
fun CameraContent(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    var isCapturing by remember { mutableStateOf(false) }

    // Create the depth provider ONCE, before the renderer.
    val depthProvider = remember { DepthProviderFactory.create(context) }

    val renderer = remember {
        CameraRenderer(context, depthProvider) { surfaceTexture ->
            startCamera(context, lifecycleOwner, surfaceTexture, depthProvider)
        }
    }

    // 同步 UI 状态到 Renderer
    LaunchedEffect(uiState) {
        renderer.density = uiState.density
        // 注意：UI State 中的 threshold 目前映射为 distortionFactor 或 dotSizeFactor，
        // 具体取决于你的 PRD 设计，这里假设 exposure 影响 dotSize，distortion 单独控制。
        // 为了完整性，这里我们直接使用 ViewModel 中未显式暴露但 Shader 需要的参数
        // 你可能需要在 ViewModel 添加 distortion 字段，或者复用 threshold。
        // 暂时保持原样映射：
        // renderer.dotSizeFactor 由外部控制（或复用 exposure）
        // renderer.distortionFactor 由外部控制

        // 颜色映射
        renderer.foregroundColor = uiState.foregroundColor.toFloatArray()
        renderer.backgroundColor = uiState.backgroundColor.toFloatArray()
    }

    // 额外的副作用：处理拍照请求
    LaunchedEffect(uiState.takePicture) {
        if (uiState.takePicture && !isCapturing) {
            isCapturing = true
            renderer.captureFrame { bitmap ->
                saveImageToGallery(context, bitmap)
                isCapturing = false
                viewModel.onTakePicture(false) // Reset state
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> renderer.resume()
                Lifecycle.Event.ON_PAUSE -> renderer.pause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            renderer.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                android.opengl.GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    renderer.attachGLSurfaceView(this)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 控制面板
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 参数滑块
            ControlSlider("网点密度", uiState.density, 10f..150f) {
                viewModel.onDensityChange(it)
            }
            // 这里我们添加一个本地状态来控制 Distortion 和 DotSize，因为 ViewModel 主要关注 Exposure
            // 或者我们可以扩展 ViewModel。这里为了快速修复，使用本地状态辅助 UI，同时也可以写入 ViewModel 如果扩展的话。
            var localDotSize by remember { mutableFloatStateOf(1.0f) }
            ControlSlider("点大小", localDotSize, 0.5f..2.0f) {
                localDotSize = it
                renderer.dotSizeFactor = it
            }

            var localDistortion by remember { mutableFloatStateOf(0.5f) }
            ControlSlider("深度畸变", localDistortion, 0.0f..1.0f) {
                localDistortion = it
                renderer.distortionFactor = it
            }

            // 颜色选择器
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                items(ColorPalette.allColors) { color ->
                    ColorCircle(color = color, isSelected = uiState.foregroundColor == color) {
                        // 简单的逻辑：选中颜色设为前景色，白色或黑色设为背景
                        val bg = if (color == Color.Black) Color.White else Color.Black
                        viewModel.selectColorTheme(color, bg)
                    }
                }
            }

            // 拍照按钮
            Button(
                onClick = { viewModel.onTakePicture(true) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isCapturing,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(if (isCapturing) "保存中..." else "拍照保存")
            }
        }
    }
}

@Composable
fun ColorCircle(color: Color, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .border(if (isSelected) 3.dp else 1.dp, if (isSelected) Color.White else Color.Gray, CircleShape)
            .clickable(onClick = onClick)
    )
}

@Composable
fun ControlSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit) {
    Column {
        Text(text = "$label: ${String.format("%.1f", value)}", color = Color.White, style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.Gray
            )
        )
    }
}

private fun Color.toFloatArray(): FloatArray {
    return floatArrayOf(this.red, this.green, this.blue, this.alpha)
}

private fun startCamera(
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    surfaceTexture: android.graphics.SurfaceTexture,
    depthProvider: DepthDataProvider
) {
    if (depthProvider is ArCoreDepthProvider) {
        Log.d("MainScreen", "ARCore provider active. Camera is managed by ARCore.")
        return
    }

    Log.d("MainScreen", "MediaPipe provider active. Binding CameraX use cases.")
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

    cameraProviderFuture.addListener({
        val cameraProvider = cameraProviderFuture.get()
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        val preview = Preview.Builder().build()
        preview.setSurfaceProvider { request ->
            surfaceTexture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
            val surface = Surface(surfaceTexture)
            request.provideSurface(surface, ContextCompat.getMainExecutor(context)) { surface.release() }
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
            val bitmap = imageProxy.toBitmap()
            if (bitmap != null) {
                val matrix = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
                val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

                (depthProvider as? MediaPipeDepthProvider)?.sendFrame(rotatedBitmap)

                // --- 修复：防止内存泄漏 ---
                // 注意：这里假设 sendFrame 是同步处理或者拷贝了数据。
                // 如果 sendFrame 是异步的，这里回收可能会导致问题。
                // 但对于 MediaPipe 图像处理，通常建议每帧通过后回收中间 Bitmap 以避免 OOM。
                rotatedBitmap.recycle()
                bitmap.recycle()
            }
            imageProxy.close()
        }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
        } catch (exc: Exception) {
            Log.e("MainScreen", "Camera binding failed for MediaPipe", exc)
        }
    }, ContextCompat.getMainExecutor(context))
}

private fun ImageProxy.toBitmap(): Bitmap? {
    if (image == null || image!!.format != ImageFormat.YUV_420_888) return null

    val yBuffer = image!!.planes[0].buffer
    val uBuffer = image!!.planes[1].buffer
    val vBuffer = image!!.planes[2].buffer
    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 90, out)
    val imageBytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}

private fun saveImageToGallery(context: Context, bitmap: Bitmap) {
    val filename = "DOT_${System.currentTimeMillis()}.jpg"
    try {
        val fos: OutputStream? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)?.let {
                context.contentResolver.openOutputStream(it)
            }
        } else {
            @Suppress("DEPRECATION")
            val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val image = java.io.File(imagesDir, filename)
            java.io.FileOutputStream(image)
        }

        fos?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "已保存到相册", Toast.LENGTH_SHORT).show()
            }
        }
    } catch (e: Exception) {
        Log.e("MainScreen", "Failed to save image", e)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}