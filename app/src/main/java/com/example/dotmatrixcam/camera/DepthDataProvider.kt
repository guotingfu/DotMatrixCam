package com.example.dotmatrixcam.camera

import android.content.Context

interface DepthDataProvider {
    // Pass camera texture ID for ARCore to draw the camera feed
    fun initialize(context: Context, width: Int, height: Int, cameraTextureId: Int)

    // Lifecycle methods
    fun resume()
    fun pause()
    fun release()

    // Frame update and data retrieval
    fun update()
    fun getDepthTextureId(): Int
}
