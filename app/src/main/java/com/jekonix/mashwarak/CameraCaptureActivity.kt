package com.jekonix.mashwarak

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class CameraCaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FACING = "camera_facing"
    }

    private lateinit var previewView: PreviewView
    private lateinit var captureButton: ImageButton
    private var imageCapture: ImageCapture? = null
    private var requestedFacing = "rear"

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "يجب السماح بالكاميرا لإكمال التسجيل",
                    Toast.LENGTH_LONG
                ).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedFacing =
            intent.getStringExtra(EXTRA_FACING)?.lowercase() ?: "rear"

        buildUi()

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val title = TextView(this).apply {
            text =
                if (requestedFacing == "front")
                    "صورة شخصية حية | الكاميرا الأمامية"
                else
                    "تصوير مباشر | الكاميرا الخلفية"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(0x77000000)
        }

        root.addView(
            title,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(22)
            }
        )

        val close = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            setBackgroundColor(0x66000000)
            contentDescription = "إغلاق"
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }

        root.addView(
            close,
            FrameLayout.LayoutParams(dp(48), dp(48)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(18)
                marginEnd = dp(16)
            }
        )

        captureButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            setColorFilter(Color.rgb(165, 17, 49))
            setBackgroundResource(R.drawable.camera_capture_button_bg)
            contentDescription = "التقاط الصورة"
            isEnabled = false
            setOnClickListener { takePhoto() }
        }

        root.addView(
            captureButton,
            FrameLayout.LayoutParams(dp(76), dp(76)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(34)
            }
        )

        setContentView(root)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)

        future.addListener({
            try {
                val provider = future.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val selector =
                    if (requestedFacing == "front")
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    else
                        CameraSelector.DEFAULT_BACK_CAMERA

                provider.unbindAll()

                provider.bindToLifecycle(
                    this,
                    selector,
                    preview,
                    imageCapture
                )

                captureButton.isEnabled = true
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    if (requestedFacing == "front")
                        "تعذر تشغيل الكاميرا الأمامية"
                    else
                        "تعذر تشغيل الكاميرا الخلفية",
                    Toast.LENGTH_LONG
                ).show()

                setResult(RESULT_CANCELED)
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        captureButton.isEnabled = false

        val directory = File(cacheDir, "camera_captures").apply {
            mkdirs()
        }

        val file = File(
            directory,
            "capture_${System.currentTimeMillis()}.jpg"
        )

        val options =
            ImageCapture.OutputFileOptions.Builder(file).build()

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(
                    outputFileResults: ImageCapture.OutputFileResults
                ) {
                    val uri: Uri = FileProvider.getUriForFile(
                        this@CameraCaptureActivity,
                        "${packageName}.fileprovider",
                        file
                    )

                    val data = Intent().apply {
                        this.data = uri
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    setResult(RESULT_OK, data)
                    finish()
                }

                override fun onError(exception: ImageCaptureException) {
                    captureButton.isEnabled = true
                    Toast.makeText(
                        this@CameraCaptureActivity,
                        "تعذر التقاط الصورة | حاول مرة أخرى",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
