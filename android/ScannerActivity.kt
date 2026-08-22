// Chuks Preview QR scanner (Android). A Camera1 preview whose frames are decoded by
// ZXing (bundled com.google.zxing:core). On a chuks:// QR it saves the host and hands
// off to MainActivity, exactly like the connect screen. Camera1 is deprecated but its
// onPreviewFrame callback hands back NV21 luminance directly — far less code than Camera2.
package com.chuks.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Camera
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.TextView
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

class ScannerActivity : Activity(), SurfaceHolder.Callback, Camera.PreviewCallback {
    private var camera: Camera? = null
    private lateinit var surface: SurfaceView
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)
    private var done = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        surface = SurfaceView(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(surface)
        root.addView(TextView(this).apply {
            text = "Point at the Chuks dev QR code"
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 120)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        surface.holder.addCallback(this)

        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 1)
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        if (g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED) {
            try { startCam(surface.holder) } catch (e: Exception) { finish() }
        } else finish()
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try { startCam(h) } catch (e: Exception) { finish() }
        }
    }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) { stopCam() }

    private fun startCam(h: SurfaceHolder) {
        stopCam()
        val cam = Camera.open() ?: run { finish(); return }
        camera = cam
        cam.setDisplayOrientation(90)
        cam.setPreviewDisplay(h)
        cam.setPreviewCallback(this)
        cam.startPreview()
    }
    private fun stopCam() {
        camera?.apply { setPreviewCallback(null); stopPreview(); release() }
        camera = null
    }

    override fun onPreviewFrame(data: ByteArray, cam: Camera) {
        if (done) return
        val sz = cam.parameters.previewSize ?: return
        try {
            val src = PlanarYUVLuminanceSource(data, sz.width, sz.height, 0, 0, sz.width, sz.height, false)
            val text = reader.decode(BinaryBitmap(HybridBinarizer(src)), hints).text
            parse(text)?.let { done = true; connect(it) }
        } catch (e: Exception) {
            // NotFound / Checksum / Format on a frame without a readable QR — keep scanning.
        } finally {
            reader.reset()
        }
    }

    private fun parse(raw: String?): String? {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        for (p in listOf("chuks://", "http://", "https://")) if (s.startsWith(p, true)) { s = s.substring(p.length); break }
        s = s.substringBefore('/')
        if (s.isEmpty()) return null
        if (!s.contains(':')) s += ":7799"
        return s
    }
    private fun connect(host: String) {
        getSharedPreferences("chuks.preview", MODE_PRIVATE).edit().putString("host", host).apply()
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }

    override fun onPause() { super.onPause(); stopCam() }
}
