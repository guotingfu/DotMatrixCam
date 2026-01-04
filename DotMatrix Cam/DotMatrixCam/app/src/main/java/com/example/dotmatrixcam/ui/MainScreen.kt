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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
fun CameraContent() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var density by remember { mutableFloatStateOf(50f) }
    var dotSize by remember { mutableFloatStateOf(1.0f) }
    var distortion by remember { mutableFloatStateOf(0.5f) }
    var isCapturing by remember { mutableStateOf(false) }

    // Create the depth provider ONCE, before the renderer.
    val depthProvider = remember { DepthProviderFactory.create(context) }

    val renderer = remember {
        CameraRenderer(context, depthProvider) { surfaceTexture ->
            startCamera(context, lifecycleOwner, surfaceTexture, depthProvider)
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

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ControlSlider("网点密度", density, 10f..150f) {
                density = it
                renderer.density = it
            }
            ControlSlider("点大小", dotSize, 0.5f..2.0f) {
                dotSize = it
                renderer.dotSizeFactor = it
            }
            ControlSlider("深度畸变", distortion, 0.0f..1.0f) {
                distortion = it
                renderer.distortionFactor = it
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (!isCapturing) {
                        isCapturing = true
                        renderer.captureFrame { bitmap ->
                            saveImageToGallery(context, bitmap)
                            isCapturing = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isCapturing
            ) {
                Text(if (isCapturing) "保存中..." else "拍照保存")
            }
        }
    }
}

@Composable
fun ControlSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit) {
    Column {
        Text(text = "$label: ${String.format("%.1f", value)}", color = Color.White)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MaterialTheme.colorScheme.primary)
        )
    }
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
