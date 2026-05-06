package com.example.scankeyboard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodService
import android.widget.ImageButton
import android.content.Intent
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.Group
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScanKeyboardService : InputMethodService(), LifecycleOwner {

    private lateinit var lifecycleRegistry: LifecycleRegistry
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    
    // UI Elements
    private lateinit var viewFinder: PreviewView
    private lateinit var btnScan: ImageButton
    private lateinit var btnPickColor: ImageButton
    private lateinit var btnDelete: ImageButton
    private lateinit var btnEnter: ImageButton
    private lateinit var btnCloseCamera: ImageButton
    private lateinit var groupKeys: Group

    private var isCameraActive = false
    @Volatile
    private var isProcessingBarcode = false

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        
        viewFinder = view.findViewById(R.id.view_finder)
        btnScan = view.findViewById(R.id.btn_scan)
        btnPickColor = view.findViewById(R.id.btn_pick_color)
        btnDelete = view.findViewById(R.id.btn_delete)
        btnEnter = view.findViewById(R.id.btn_enter)
        btnCloseCamera = view.findViewById(R.id.btn_close_camera)
        groupKeys = view.findViewById(R.id.group_keys)

        btnScan.setOnClickListener {
            if (checkCameraPermission()) {
                startCameraMode()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            }
        }

        btnPickColor.setOnClickListener {
            val intent = Intent(this, ColorPickerActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }

        btnDelete.setOnClickListener {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }

        btnEnter.setOnClickListener {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }

        btnCloseCamera.setOnClickListener {
            stopCameraMode()
        }

        return view
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        KeyboardSharedState.pendingText?.let { text ->
            currentInputConnection?.commitText(text, 1)
            KeyboardSharedState.pendingText = null
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (isCameraActive) {
            stopCameraMode()
        }
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        cameraExecutor.shutdown()
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCameraMode() {
        isCameraActive = true
        isProcessingBarcode = false
        groupKeys.visibility = View.GONE
        viewFinder.visibility = View.VISIBLE
        btnCloseCamera.visibility = View.VISIBLE
        startCamera()
    }

    private fun stopCameraMode() {
        isCameraActive = false
        viewFinder.visibility = View.GONE
        btnCloseCamera.visibility = View.GONE
        groupKeys.visibility = View.VISIBLE
        cameraProvider?.unbindAll()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, BarcodeAnalyzer { barcodes ->
                        if (barcodes.isNotEmpty() && !isProcessingBarcode) {
                             isProcessingBarcode = true
                             val value = barcodes[0].rawValue
                             if (value != null) {
                                 onBarcodeDetected(value)
                             } else {
                                 isProcessingBarcode = false
                             }
                        }
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                // Handle errors
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun onBarcodeDetected(result: String) {
        ContextCompat.getMainExecutor(this).execute {
            // Stop scanning immediately to prevent duplicate inputs
            stopCameraMode()
            currentInputConnection?.commitText(result, 1)
            triggerVibration()
        }
    }

    private fun triggerVibration() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(100)
        }
    }

    private class BarcodeAnalyzer(private val listener: (List<Barcode>) -> Unit) : ImageAnalysis.Analyzer {
        @SuppressLint("UnsafeOptInUsageError")
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                val scanner = BarcodeScanning.getClient()
                
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        if (barcodes.isNotEmpty()) {
                            listener(barcodes)
                        }
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }
    }
}
