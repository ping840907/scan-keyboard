package com.example.scankeyboard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.Group
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.scankeyboard.camera.FrameMetrics
import com.example.scankeyboard.camera.setCovered
import com.example.scankeyboard.camera.setFrameRoi
import de.markusfisch.android.zxingcpp.ZxingCpp
import de.markusfisch.android.zxingcpp.ZxingCpp.Binarizer
import de.markusfisch.android.zxingcpp.ZxingCpp.ReaderOptions
import de.markusfisch.android.zxingcpp.ZxingCpp.TextMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

class ScanKeyboardService : InputMethodService(), LifecycleOwner {

    private lateinit var lifecycleRegistry: LifecycleRegistry
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null

    // UI Elements
    private lateinit var viewFinder: PreviewView
    private lateinit var btnScan: ImageButton
    private lateinit var btnDelete: ImageButton
    private lateinit var btnEnter: ImageButton
    private lateinit var btnSwitchKeyboard: ImageButton
    private lateinit var btnCloseCamera: ImageButton
    private lateinit var btnFlash: ImageButton
    private lateinit var btnAutoEnter: ImageButton
    private lateinit var sliderZoom: SeekBar
    private lateinit var groupKeys: Group

    private var isCameraActive = false
    @Volatile
    private var isProcessingBarcode = false
    private var camera: Camera? = null
    private var isFlashOn = false
    private var isAutoEnterEnabled = true
    private var useLocalAverage = false

    // Backspace continuous delete handler
    private val deleteHandler = Handler(Looper.getMainLooper())
    private val deleteRunnable = object : Runnable {
        override fun run() {
            handleDelete()
            deleteHandler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    // Reader options tuned for maximum accuracy and versatility (Binary Eye technique)
    private val readerOptions = ReaderOptions(
        tryHarder = true,
        tryRotate = true,
        tryInvert = true,
        tryDownscale = true,
        maxNumberOfSymbols = 1,
        textMode = TextMode.PLAIN
    )

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)

        // Ensure minimum and layout height matches keyboard height (300dp) so it doesn't collapse in camera mode
        val heightInPx = (300 * resources.displayMetrics.density).toInt()
        view.minimumHeight = heightInPx
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightInPx)

        viewFinder = view.findViewById(R.id.view_finder)
        btnScan = view.findViewById(R.id.btn_scan)
        btnDelete = view.findViewById(R.id.btn_delete)
        btnEnter = view.findViewById(R.id.btn_enter)
        btnSwitchKeyboard = view.findViewById(R.id.btn_switch_keyboard)
        btnCloseCamera = view.findViewById(R.id.btn_close_camera)
        btnFlash = view.findViewById(R.id.btn_flash)
        btnAutoEnter = view.findViewById(R.id.btn_auto_enter)
        sliderZoom = view.findViewById(R.id.slider_zoom)
        groupKeys = view.findViewById(R.id.group_keys)

        btnSwitchKeyboard.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        }

        btnScan.setOnClickListener {
            if (checkCameraPermission()) {
                startCameraMode()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            }
        }

        // Setup continuous delete and selected text deletion
        btnDelete.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    handleDelete()
                    deleteHandler.removeCallbacks(deleteRunnable)
                    deleteHandler.postDelayed(deleteRunnable, INITIAL_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.x < 0 || event.x > v.width || event.y < 0 || event.y > v.height) {
                        v.isPressed = false
                        deleteHandler.removeCallbacks(deleteRunnable)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    deleteHandler.removeCallbacks(deleteRunnable)
                    true
                }
                else -> false
            }
        }

        btnEnter.setOnClickListener {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }

        btnAutoEnter.setOnClickListener {
            isAutoEnterEnabled = !isAutoEnterEnabled
            btnAutoEnter.setImageResource(
                if (isAutoEnterEnabled) R.drawable.ic_auto_enter_on else R.drawable.ic_auto_enter_off
            )
        }

        btnCloseCamera.setOnClickListener {
            stopCameraMode()
        }

        btnFlash.setOnClickListener {
            toggleFlash()
        }

        setupTouchAndZoom()

        return view
    }

    private fun handleDelete() {
        val ic = currentInputConnection ?: return
        val selectedText = ic.getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
            // Delete the highlighted / selected text
            ic.commitText("", 1)
        } else {
            // Send standard DEL key event
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchAndZoom() {
        // Pinch-to-zoom setup
        val scaleGestureDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val zoomState = camera?.cameraInfo?.zoomState?.value ?: return false
                    val currentZoomRatio = zoomState.zoomRatio
                    val delta = detector.scaleFactor
                    val newZoomRatio = currentZoomRatio * delta
                    camera?.cameraControl?.setZoomRatio(newZoomRatio)

                    val minZoom = zoomState.minZoomRatio
                    val maxZoom = zoomState.maxZoomRatio
                    if (maxZoom > minZoom) {
                        val progress = ((newZoomRatio - minZoom) / (maxZoom - minZoom) * 100).toInt()
                        sliderZoom.progress = progress.coerceIn(0, 100)
                    }
                    return true
                }
            }
        )

        var downX = 0f
        var downY = 0f
        var hasMoved = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        // Tap-to-focus and pinch zoom combination
        viewFinder.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    hasMoved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > touchSlop) {
                        hasMoved = true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!hasMoved && !scaleGestureDetector.isInProgress) {
                        camera?.cameraControl?.let { control ->
                            try {
                                val factory = viewFinder.meteringPointFactory
                                val point = factory.createPoint(event.x, event.y)
                                val action = FocusMeteringAction.Builder(point).build()
                                control.startFocusAndMetering(action)
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
            true
        }

        // SeekBar zoom setup
        sliderZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    camera?.cameraControl?.setLinearZoom(progress / 100f)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun toggleFlash() {
        camera?.let {
            if (it.cameraInfo.hasFlashUnit()) {
                isFlashOn = !isFlashOn
                it.cameraControl.enableTorch(isFlashOn)
                btnFlash.setImageResource(if (isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
            }
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        deleteHandler.removeCallbacks(deleteRunnable)
        if (isCameraActive) {
            stopCameraMode()
        }
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onDestroy() {
        super.onDestroy()
        deleteHandler.removeCallbacks(deleteRunnable)
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
        isFlashOn = false
        btnFlash.setImageResource(R.drawable.ic_flash_off)
        groupKeys.visibility = View.GONE
        viewFinder.visibility = View.VISIBLE
        btnCloseCamera.visibility = View.VISIBLE
        btnFlash.visibility = View.VISIBLE
        sliderZoom.visibility = View.VISIBLE
        sliderZoom.progress = 0
        startCamera()
    }

    private fun stopCameraMode() {
        isCameraActive = false
        viewFinder.visibility = View.GONE
        btnCloseCamera.visibility = View.GONE
        btnFlash.visibility = View.GONE
        sliderZoom.visibility = View.GONE
        groupKeys.visibility = View.VISIBLE
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        preview?.setSurfaceProvider(null)
        preview = null
        cameraProvider?.unbindAll()
        camera = null
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // High-resolution selector matching Binary Eye's strategy
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
                )
                .setAspectRatioStrategy(
                    AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                )
                .setAllowedResolutionMode(
                    ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
                )
                .build()

            val previewUseCase = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build().also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            val analysisUseCase = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        analyzeImage(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    this, cameraSelector, previewUseCase, analysisUseCase
                )
                this.preview = previewUseCase
                this.imageAnalysis = analysisUseCase
            } catch (_: Exception) {
                // Handle errors
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeImage(image: ImageProxy) {
        try {
            if (!isCameraActive || isProcessingBarcode) {
                return
            }

            val frameMetrics = FrameMetrics(
                image.width,
                image.height,
                image.imageInfo.rotationDegrees
            )

            val viewWidth = viewFinder.width
            val viewHeight = viewFinder.height

            // Calculate precise ROI in frame coordinates for current viewfinder
            val frameRoi = if (viewWidth > 0 && viewHeight > 0) {
                val previewRect = Rect().apply {
                    setCovered(viewWidth, viewHeight, frameMetrics)
                }
                val viewRoi = Rect(0, 0, viewWidth, viewHeight)
                Rect().apply {
                    setFrameRoi(frameMetrics, previewRect, viewRoi)
                }
            } else {
                Rect(0, 0, image.width, image.height)
            }

            if (frameRoi.width() < 1 || frameRoi.height() < 1) {
                return
            }

            // Dual-Binarizer Alternation: Toggle between LOCAL_AVERAGE and GLOBAL_HISTOGRAM
            useLocalAverage = useLocalAverage xor true
            readerOptions.binarizer = if (useLocalAverage) {
                Binarizer.LOCAL_AVERAGE
            } else {
                Binarizer.GLOBAL_HISTOGRAM
            }

            // Zero-copy Y-plane luminance buffer read via native C++ ZXing
            val yPlane = image.planes[0]
            val results = ZxingCpp.readYBuffer(
                yPlane.buffer,
                yPlane.rowStride,
                frameRoi,
                frameMetrics.orientation,
                readerOptions
            )

            results?.firstOrNull()?.let { result ->
                val text = result.text
                if (!text.isNullOrEmpty() && !isProcessingBarcode) {
                    isProcessingBarcode = true
                    onBarcodeDetected(text)
                }
            }
        } catch (_: Exception) {
            // Handle exceptions
        } finally {
            image.close()
        }
    }

    private fun onBarcodeDetected(result: String) {
        ContextCompat.getMainExecutor(this).execute {
            // Stop scanning immediately to prevent duplicate inputs
            stopCameraMode()
            currentInputConnection?.commitText(result, 1)
            if (isAutoEnterEnabled) {
                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
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

    companion object {
        private const val INITIAL_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 50L
    }
}
