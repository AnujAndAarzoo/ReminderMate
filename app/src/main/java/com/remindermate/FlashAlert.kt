package com.remindermate

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log

object FlashAlert {
    fun blink(context: Context) {
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = try {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {
            Log.w("ReminderMate", "Camera flash is unavailable", e)
            null
        } ?: return

        val handler = Handler(Looper.getMainLooper())
        val pattern = longArrayOf(0L, 250L, 250L, 250L, 250L, 250L, 250L)
        pattern.forEachIndexed { index, delay ->
            handler.postDelayed({
                try {
                    manager.setTorchMode(cameraId, index % 2 == 0)
                } catch (e: Exception) {
                    Log.w("ReminderMate", "Unable to control camera flash", e)
                }
                if (index == pattern.lastIndex) {
                    handler.postDelayed({
                        try { manager.setTorchMode(cameraId, false) } catch (_: Exception) { }
                    }, 250L)
                }
            }, pattern.take(index + 1).sum())
        }
    }
}
