package com.example.scankeyboard

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class ColorPickerActivity : AppCompatActivity() {

    private lateinit var ivPreview: ImageView
    private lateinit var vColorPreview: View
    private lateinit var tvHexCode: TextView
    private lateinit var btnGallery: Button
    private lateinit var btnCamera: Button
    private lateinit var btnConfirm: Button

    private var currentHexCode: String = ""
    private var imageUri: Uri? = null

    private val selectImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            ivPreview.setImageURI(uri)
        }
    }

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success: Boolean ->
        if (success) {
            imageUri?.let { ivPreview.setImageURI(it) }
        }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            launchCamera()
        } else {
            Toast.makeText(this, "Camera permission required for taking photos", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_color_picker)

        ivPreview = findViewById(R.id.iv_preview)
        vColorPreview = findViewById(R.id.v_color_preview)
        tvHexCode = findViewById(R.id.tv_hex_code)
        btnGallery = findViewById(R.id.btn_gallery)
        btnCamera = findViewById(R.id.btn_camera)
        btnConfirm = findViewById(R.id.btn_confirm)

        btnGallery.setOnClickListener {
            selectImageLauncher.launch("image/*")
        }

        btnCamera.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        ivPreview.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                val drawable = ivPreview.drawable
                if (drawable is BitmapDrawable) {
                    val bitmap = drawable.bitmap
                    if (bitmap != null) {
                        val matrix = Matrix()
                        ivPreview.imageMatrix.invert(matrix)
                        val pts = floatArrayOf(event.x, event.y)
                        matrix.mapPoints(pts)

                        val x = pts[0].toInt()
                        val y = pts[1].toInt()

                        if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                            val pixel = bitmap.getPixel(x, y)

                            val red = Color.red(pixel)
                            val green = Color.green(pixel)
                            val blue = Color.blue(pixel)

                            val hex = String.format("#%02X%02X%02X", red, green, blue)
                            currentHexCode = hex
                            tvHexCode.text = hex
                            vColorPreview.setBackgroundColor(Color.parseColor(hex))
                        }
                    }
                }
            }
            true
        }

        btnConfirm.setOnClickListener {
            if (currentHexCode.isNotEmpty()) {
                KeyboardSharedState.pendingText = currentHexCode
                finish()
            } else {
                Toast.makeText(this, "Please select a color first", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchCamera() {
        val values = ContentValues()
        values.put(MediaStore.Images.Media.TITLE, "New Picture")
        values.put(MediaStore.Images.Media.DESCRIPTION, "From Camera")
        imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

        imageUri?.let {
            takePictureLauncher.launch(it)
        }
    }
}
