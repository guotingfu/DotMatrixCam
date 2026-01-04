package com.example.dotmatrixcam

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.dotmatrixcam.ui.MainScreen
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale

@OptIn(ExperimentalPermissionsApi::class)
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置全屏沉浸式
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // 请求相机权限
                    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

                    when (cameraPermissionState.status) {
                        is PermissionStatus.Granted -> {
                            // 权限通过，直接显示主界面 (不再传递 viewModel)
                            MainScreen()
                        }
                        is PermissionStatus.Denied -> {
                            if (cameraPermissionState.status.shouldShowRationale) {
                                RationaleScreen {
                                    cameraPermissionState.launchPermissionRequest()
                                }
                            } else {
                                RationaleScreen {
                                    cameraPermissionState.launchPermissionRequest()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RationaleScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "应用需要相机权限才能运行",
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRequestPermission) {
                Text("授予权限")
            }
        }
    }
}