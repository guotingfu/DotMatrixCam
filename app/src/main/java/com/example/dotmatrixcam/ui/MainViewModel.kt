package com.example.dotmatrixcam.ui

import androidx.camera.core.CameraSelector
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// Represents the rendering mode
enum class RenderMode(val shaderValue: Int) {
    STANDARD(0),
    RHYTHMIC(1)
}

// Represents the exposure mode
enum class ExposureMode {
    AUTO,
    MANUAL
}

// Holds the entire state for the UI and the camera effect
data class CameraUiState(
    val density: Float = 60f,
    val threshold: Float = 0.5f, // Represents manual exposure value
    val renderMode: RenderMode = RenderMode.STANDARD,
    val cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
    val takePicture: Boolean = false,
    val exposureMode: ExposureMode = ExposureMode.AUTO,
    val foregroundColor: Color = Color.Black,
    val backgroundColor: Color = Color.White,
    val showCustomColorPicker: Boolean = false
)

// Pre-defined color palette for custom selection
object ColorPalette {
    val Red = Color(0xFFE53935)
    val Yellow = Color(0xFFFFEB3B)
    val Green = Color(0xFF43A047)
    val Blue = Color(0xFF1E88E5)
    val Black = Color.Black
    val White = Color.White

    val allColors = listOf(Red, Yellow, Green, Blue, Black, White)
}

class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState = _uiState.asStateFlow()

    fun onDensityChange(newDensity: Float) {
        _uiState.update { it.copy(density = newDensity) }
    }

    fun onThresholdChange(newThreshold: Float) {
        _uiState.update { it.copy(threshold = newThreshold, exposureMode = ExposureMode.MANUAL) }
    }

    fun onRenderModeChange() {
        val newMode = if (uiState.value.renderMode == RenderMode.STANDARD) RenderMode.RHYTHMIC else RenderMode.STANDARD
        _uiState.update { it.copy(renderMode = newMode) }
    }

    fun onTakePicture(takePicture: Boolean) {
        _uiState.update { it.copy(takePicture = takePicture) }
    }

    fun onCameraFlip() {
        val newSelector = if (uiState.value.cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        _uiState.update { it.copy(cameraSelector = newSelector) }
    }

    fun setAutoExposure() {
        _uiState.update { it.copy(exposureMode = ExposureMode.AUTO) }
    }

    fun setAutoExposureValue(autoExposureValue: Float) {
        if (uiState.value.exposureMode == ExposureMode.AUTO) {
            _uiState.update { it.copy(threshold = autoExposureValue) }
        }
    }

    fun selectColorTheme(fg: Color, bg: Color) {
        _uiState.update { it.copy(foregroundColor = fg, backgroundColor = bg, showCustomColorPicker = false) }
    }

    fun toggleCustomColorPicker() {
        _uiState.update { it.copy(showCustomColorPicker = !it.showCustomColorPicker) }
    }

    fun setForegroundColor(color: Color) {
        _uiState.update { it.copy(foregroundColor = color) }
    }

    fun setBackgroundColor(color: Color) {
        _uiState.update { it.copy(backgroundColor = color) }
    }
}
