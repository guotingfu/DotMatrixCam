package com.example.dotmatrixcam.camera

import android.content.Context
import com.google.ar.core.ArCoreApk

object DepthProviderFactory {
    fun create(context: Context): DepthDataProvider {
        val availability = ArCoreApk.getInstance().checkAvailability(context)
        return if (availability.isSupported) {
            ArCoreDepthProvider()
        } else {
            MediaPipeDepthProvider()
        }
    }
}
