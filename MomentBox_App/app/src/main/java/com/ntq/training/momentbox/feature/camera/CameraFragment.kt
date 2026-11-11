package com.ntq.training.momentbox.feature.camera

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.util.Rational
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.RelativeCornerSize
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.media.AndroidPlaybackAudio
import com.ntq.training.momentbox.core.media.AudioPlaybackKind
import com.ntq.training.momentbox.core.media.OwnedImageFiles
import com.ntq.training.momentbox.core.media.StaticPcmSound
import com.ntq.training.momentbox.databinding.FragmentCameraBinding
import com.ntq.training.momentbox.feature.create.CreateViewModel
import com.ntq.training.momentbox.feature.recorder.RecordPhase
import com.ntq.training.momentbox.feature.recorder.RecordingService
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** CameraX gắn với viewLifecycleOwner. File chỉ trả sau capture/finalize thành công. */
private const val VIDEO_HOLD_THRESHOLD_MS = 250L

class CameraFragment : Fragment(R.layout.fragment_camera) {
    private var binding: FragmentCameraBinding? = null
    private val create: CreateViewModel by activityViewModels()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var observedZoom: LiveData<ZoomState>? = null
    private var capture: ImageCapture? = null
    private var video: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var analysis: ImageAnalysis? = null
    private var analyzer: RgbaAnalyzer? = null
    private var executor = Executors.newSingleThreadExecutor()
    private var bindJob: Job? = null
    private var shutterJob: Job? = null
    private var shutterSound: StaticPcmSound? = null
    private var tick: StaticPcmSound? = null
    private val model: CameraViewModel by viewModels()
    private var front: Boolean
        get() = model.front
        set(value) {
            model.front = value
        }

    private var videoMode: Boolean
        get() = model.video
        set(value) {
            model.video = value
        }

    private var ratio: Int
        get() = model.ratio
        set(value) {
            model.ratio = value
        }

    private var timer: Int
        get() = model.timer
        set(value) {
            model.timer = value
        }

    private var flash: Int
        get() = model.flash
        set(value) {
            model.flash = value
        }

    private var filter: PhotoFilter
        get() = PhotoFilter.entries.getOrElse(model.filter) { PhotoFilter.ORIGINAL }
        set(value) {
            model.filter = value.ordinal
        }

    private var captureBusy = false
    private var activeVideoFile: File? = null
    private var validRecording = true
    private val audioOwner = Any()
    private val playbackAudio by lazy { AndroidPlaybackAudio.get(requireContext()) }
    private var videoStartJob: Job? = null
    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            bindCamera()
        }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(requireContext(), p) == PackageManager.PERMISSION_GRANTED

    override fun onViewCreated(view: View, state: Bundle?) {
        val b = FragmentCameraBinding.bind(view)
        binding = b
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (b.cameraHeader.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = bars.top + dp(6)
                b.cameraHeader.layoutParams = this
            }
            insets
        }
        ViewCompat.requestApplyInsets(view)
        b.cameraLatest.shapeAppearanceModel =
            b.cameraLatest.shapeAppearanceModel
                .toBuilder()
                .setAllCorners(CornerFamily.ROUNDED, dp(12).toFloat())
                .build()
        b.cameraOverlay.filter = filter
        shutterSound =
            runCatching { StaticPcmSound(requireContext(), "audio/sfx-shutter.wav") }.getOrNull()
        tick = runCatching { StaticPcmSound(requireContext(), "audio/sfx-tick.wav") }.getOrNull()
        b.cameraClose.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        b.cameraError.setOnClickListener {
            if (granted(Manifest.permission.CAMERA)) bindCamera()
            else permissions.launch(arrayOf(Manifest.permission.CAMERA))
        }
        b.cameraSwitch.setOnClickListener {
            if (!captureBusy && recording == null) {
                front = !front
                bindCamera()
            }
        }
        b.cameraFlash.setOnClickListener {
            flash =
                when (flash) {
                    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                    ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                    else -> ImageCapture.FLASH_MODE_AUTO
                }
            capture?.flashMode = flash
            renderControls()
        }
        b.cameraTimer.setOnClickListener {
            timer =
                when (timer) {
                    0 -> 3
                    3 -> 10
                    else -> 0
                }
            renderControls()
        }
        b.cameraRatio.setOnClickListener {
            if (!captureBusy && recording == null) {
                ratio = (ratio + 1) % 3
                bindCamera()
            }
        }
        childFragmentManager.setFragmentResultListener("camera_options", viewLifecycleOwner) {
            _,
            result ->
            when (result.getString("action")) {
                "flash" -> b.cameraFlash.performClick()
                "timer" -> b.cameraTimer.performClick()
                "ratio" -> b.cameraRatio.performClick()
            }
        }
        b.cameraSettings.setOnClickListener {
            if (
                childFragmentManager.isStateSaved ||
                    childFragmentManager.findFragmentByTag("camera-options") != null ||
                    captureBusy ||
                    recording != null
            )
                return@setOnClickListener
            val actions = mutableListOf<String>()

            val labels = mutableListOf<String>()
            if (b.cameraFlash.isVisible) {
                actions += "flash"
                labels +=
                    getString(R.string.m4_camera_flash_option, b.cameraFlash.contentDescription)
            }
            if (!videoMode) {
                actions += "timer"
                labels += getString(R.string.m4_camera_timer_option, b.cameraTimer.text)
                actions += "ratio"
                labels += getString(R.string.m4_camera_ratio_option, b.cameraRatio.text)
            }
            CameraOptionsDialog()
                .apply {
                    arguments =
                        Bundle().apply {
                            putStringArray("actions", actions.toTypedArray())
                            putStringArray("labels", labels.toTypedArray())
                        }
                }
                .show(childFragmentManager, "camera-options")
        }
        b.cameraPhotoMode.setOnClickListener { changeMode(false) }
        b.cameraVideoMode.isVisible = !create.imagesOnly
        b.cameraVideoMode.setOnClickListener { changeMode(true) }
        setupModeSwipe(b)
        val latestPhoto = create.items.value.lastOrNull { !it.isVideo }
        b.cameraLatest.isEnabled = latestPhoto != null
        if (latestPhoto != null) {
            Glide.with(this)
                .load(latestPhoto.uri)
                .centerCrop()
                .into(b.cameraLatest)
            b.cameraLatest.setOnClickListener {
                if (!captureBusy && recording == null) {
                    parentFragmentManager.setFragmentResult(
                        "create_crop_latest",
                        Bundle().apply { putString("id", latestPhoto.id) },
                    )
                }
            }
        }
        val names =
            intArrayOf(
                R.string.m4_filter_original,
                R.string.m4_filter_warm,
                R.string.m4_filter_cool,
                R.string.m4_filter_mono,
                R.string.m4_filter_vintage,
                R.string.m4_filter_negative,
            )
        val thumbnails = mutableListOf<ShapeableImageView>()
        val labels = mutableListOf<TextView>()
        PhotoFilter.entries.forEachIndexed { i, f ->
            val column =
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams =
                        LinearLayout.LayoutParams(
                            dp(66),
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                }
            val image =
                ShapeableImageView(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    // Corner tương đối giữ thumbnail tròn cả khi path stroke bị inset vào trong.
                    shapeAppearanceModel =
                        shapeAppearanceModel
                            .toBuilder()
                            .setAllCorners(CornerFamily.ROUNDED, 0f)
                            .setAllCornerSizes(RelativeCornerSize(.5f))
                            .build()
                    strokeColor =
                        ColorStateList.valueOf(Color.WHITE)
                    strokeWidth = if (f == filter) 2.5f * resources.displayMetrics.density else 0f
                    colorFilter = ColorMatrixColorFilter(PhotoFilters.matrix(f))
                }
            // Dựng lại View sau rotation vẫn phải giữ kiểu chữ bold của filter đang chọn.
            val label =
                TextView(requireContext()).apply {
                    text = getString(names[i])
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    typeface =
                        ResourcesCompat.getFont(
                            requireContext(),
                            if (f == filter) R.font.be_vietnam_pro_bold
                            else R.font.be_vietnam_pro_regular,
                        )
                    setSingleLine(true)
                }
            // Khay cuộn được: nới cột theo chữ/font thật để nhãn Đen trắng không bị xuống2dòng.
            column.layoutParams.width =
                maxOf(
                    dp(66),
                    kotlin.math.ceil(label.paint.measureText(label.text.toString())).toInt() + dp(8),
                )
            label.layoutParams =
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    .apply { topMargin = dp(5) }
            column.addView(image)
            column.addView(label)
            b.cameraFilters.addView(column)
            thumbnails.add(image)
            labels.add(label)
            column.alpha = if (f == filter) 1f else .85f
            column.setOnClickListener {
                filter = f
                b.cameraOverlay.filter = f
                thumbnails.forEachIndexed { n, v ->
                    v.strokeWidth = if (n == i) 2.5f * resources.displayMetrics.density else 0f
                    b.cameraFilters.getChildAt(n).alpha = if (n == i) 1f else .85f
                }
                labels.forEachIndexed { n, v ->
                    v.typeface =
                        ResourcesCompat.getFont(
                            requireContext(),
                            if (n == i) R.font.be_vietnam_pro_bold
                            else R.font.be_vietnam_pro_regular,
                        )
                }
            }
        }
        val band =
            maxOf(
                dp(76),
                dp(61) +
                    kotlin.math
                        .ceil(
                            labels
                                .maxOf { it.paint.fontMetrics.bottom - it.paint.fontMetrics.top }
                                .toDouble()
                        )
                        .toInt(),
            )
        (b.cameraFilters.parent as View).layoutParams.height = band
        var thumbnailBitmap: Bitmap? = null
        b.cameraOverlay.onThumbnail = { bitmap ->
            if (binding === b) {
                if (thumbnailBitmap === bitmap) thumbnails.forEach { it.invalidate() }
                else {
                    thumbnailBitmap = bitmap
                    thumbnails.forEach { it.setImageBitmap(bitmap) }
                }
            }
        }
        val scale =
            ScaleGestureDetector(
                requireContext(),
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        camera?.let { c ->
                            c.cameraInfo.zoomState.value?.let { z ->
                                c.cameraControl.setZoomRatio(
                                    (z.zoomRatio * detector.scaleFactor).coerceIn(
                                        z.minZoomRatio,
                                        z.maxZoomRatio,
                                    )
                                )
                            }
                        }
                        return true
                    }
                },
            )
        val overlay = b.cameraOverlay
        val hideFocus = Runnable {
            overlay.focusX = -1f
            overlay.invalidate()
        }
        var downY = 0f
        var downX = 0f
        var multiple = false
        var exposureDrag = false
        var startExposure = 0
        var requestedExposure = 0
        overlay.setOnTouchListener { _, event ->
            scale.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.y
                    downX = event.x
                    multiple = false
                    exposureDrag = false
                    startExposure =
                        camera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0
                    requestedExposure = startExposure
                }

                MotionEvent.ACTION_POINTER_DOWN -> multiple = true
                MotionEvent.ACTION_MOVE ->
                    if (
                        !multiple &&
                            (exposureDrag ||
                                (kotlin.math.abs(event.y - downY) > dp(12) &&
                                    kotlin.math.abs(event.y - downY) >
                                        kotlin.math.abs(event.x - downX)))
                    ) {
                        camera?.let { c ->
                            val e = c.cameraInfo.exposureState
                            if (e.isExposureCompensationSupported) {
                                if (!exposureDrag) {
                                    exposureDrag = true
                                    overlay.removeCallbacks(hideFocus)
                                    if (overlay.focusX < 0) {
                                        overlay.focusX = downX
                                        overlay.focusY = downY
                                    }
                                }
                                val range = e.exposureCompensationRange
                                val index =
                                    (startExposure + Math.round((downY - event.y) / dp(24)))
                                        .coerceIn(range.lower, range.upper)
                                if (index != requestedExposure) {
                                    requestedExposure = index
                                    c.cameraControl.setExposureCompensationIndex(index)
                                }
                                overlay.exposureFraction =
                                    (index - range.lower).toFloat() /
                                        (range.upper - range.lower).coerceAtLeast(1)
                                overlay.invalidate()
                            }
                        }
                    }

                MotionEvent.ACTION_UP -> {
                    if (exposureDrag) {
                        overlay.postDelayed(hideFocus, 2000)
                    } else if (
                        !multiple &&
                            !swipeCameraMode(event.x - downX, event.y - downY) &&
                            kotlin.math.abs(event.x - downX) < dp(15) &&
                            kotlin.math.abs(event.y - downY) < dp(15)
                    ) {
                        val point =
                            b.cameraPreview.meteringPointFactory.createPoint(event.x, event.y)
                        camera
                            ?.cameraControl
                            ?.startFocusAndMetering(
                                FocusMeteringAction.Builder(point)
                                    .setAutoCancelDuration(2, TimeUnit.SECONDS)
                                    .build()
                            )
                        overlay.focusX = event.x
                        overlay.focusY = event.y
                        overlay.invalidate()
                        overlay.removeCallbacks(hideFocus)
                        overlay.postDelayed(hideFocus, 2000)
                    }
                }

                MotionEvent.ACTION_CANCEL -> if (exposureDrag) overlay.postDelayed(hideFocus, 2000)
            }
            true
        }
        b.cameraShutter.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (videoMode) startVideo() else takePhoto()
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL ->
                    if (videoMode) {
                        videoStartJob?.cancel()
                        recording?.stop()
                        if (
                            event.actionMasked == MotionEvent.ACTION_UP &&
                                event.eventTime - event.downTime < VIDEO_HOLD_THRESHOLD_MS &&
                                video != null &&
                                granted(Manifest.permission.RECORD_AUDIO)
                        ) {
                            error(R.string.m4_video_hold_hint)
                        }
                    }
            }
            true
        }
        if (granted(Manifest.permission.CAMERA)) bindCamera()
        else {
            error(R.string.m4_camera_permission)
            permissions.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun setupModeSwipe(b: FragmentCameraBinding) {
        var downX = 0f
        var downY = 0f
        var multiple = false
        val listener =
            View.OnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        multiple = false
                        view.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> multiple = true
                    MotionEvent.ACTION_UP -> {
                        view.parent.requestDisallowInterceptTouchEvent(false)
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (
                            !multiple &&
                                !swipeCameraMode(dx, dy) &&
                                kotlin.math.abs(dx) < dp(15) &&
                                kotlin.math.abs(dy) < dp(15)
                        )
                            view.performClick()
                    }
                    MotionEvent.ACTION_CANCEL ->
                        view.parent.requestDisallowInterceptTouchEvent(false)
                }
                true
            }
        b.cameraModes.setOnTouchListener(listener)
        b.cameraPhotoMode.setOnTouchListener(listener)
        b.cameraVideoMode.setOnTouchListener(listener)
    }

    private fun swipeCameraMode(dx: Float, dy: Float): Boolean {
        val horizontal = kotlin.math.abs(dx)
        if (horizontal < dp(32) || horizontal <= kotlin.math.abs(dy) * 1.5f) return false
        changeMode(dx > 0)
        return true
    }

    private fun changeMode(video: Boolean) {
        if (video && create.imagesOnly) return
        if (!captureBusy && recording == null && videoMode != video) {
            videoMode = video
            bindCamera()
        }
    }

    private suspend fun awaitProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(requireContext().applicationContext)
            future.addListener(
                {
                    try {
                        val result = future.get()
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                },
                ContextCompat.getMainExecutor(requireContext()),
            )
            // Provider là singleton dùng chung, hủy await không gọi unbindAll làm hỏng owner mới.
        }

    private fun bindCamera() {
        val b = binding ?: return
        if (!granted(Manifest.permission.CAMERA)) return
        bindJob?.cancel()
        bindJob =
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val p = awaitProvider()
                    if (binding !== b) return@launch
                    provider = p
                    p.unbindAll()
                    analyzer?.close()
                    val wide = videoMode || ratio == 1
                    val aspect =
                        if (wide) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                        else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                    val rotation =
                        b.cameraPreview.display?.rotation ?: Surface.ROTATION_0
                    val landscape =
                        resources.configuration.orientation ==
                            Configuration.ORIENTATION_LANDSCAPE
                    val viewportAspect =
                        if (ratio == 2 && !videoMode) Rational(1, 1)
                        else if (wide) {
                            if (landscape) Rational(16, 9)
                            else Rational(9, 16)
                        } else {
                            if (landscape) Rational(4, 3)
                            else Rational(3, 4)
                        }
                    val viewport =
                        ViewPort.Builder(viewportAspect, rotation)
                            .setScaleType(ViewPort.FILL_CENTER)
                            .build()
                    b.cameraPreview.scaleType =
                        PreviewView.ScaleType.FILL_CENTER
                    b.cameraOverlay.showFrames = !videoMode
                    val preview =
                        Preview.Builder()
                            .setTargetRotation(rotation)
                            .setResolutionSelector(
                                ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build()
                            )
                            .build()
                            .also { it.surfaceProvider = b.cameraPreview.surfaceProvider }
                    val selector =
                        if (front) CameraSelector.DEFAULT_FRONT_CAMERA
                        else CameraSelector.DEFAULT_BACK_CAMERA
                    if (videoMode) {
                        val v =
                            VideoCapture.withOutput(
                                Recorder.Builder()
                                    .setQualitySelector(
                                        QualitySelector.from(
                                            Quality.HD,
                                            FallbackStrategy.lowerQualityOrHigherThan(Quality.HD),
                                        )
                                    )
                                    .build()
                            )
                        video = v
                        capture = null
                        analysis = null
                        camera =
                            p.bindToLifecycle(
                                viewLifecycleOwner,
                                selector,
                                UseCaseGroup.Builder()
                                    .setViewPort(viewport)
                                    .addUseCase(preview)
                                    .addUseCase(v)
                                    .build(),
                            )
                        b.cameraOverlay.visibility = View.VISIBLE
                    } else {
                        val c =
                            ImageCapture.Builder()
                                .setTargetRotation(rotation)
                                .setResolutionSelector(
                                    ResolutionSelector.Builder()
                                        .setAspectRatioStrategy(aspect)
                                        .build()
                                )
                                .setFlashMode(flash)
                                .build()
                        val a =
                            ImageAnalysis.Builder()
                                .setTargetRotation(rotation)
                                .setResolutionSelector(
                                    ResolutionSelector.Builder()
                                        .setAspectRatioStrategy(
                                            AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                                        )
                                        .setResolutionStrategy(
                                            ResolutionStrategy(
                                                Size(1280, 720),
                                                ResolutionStrategy
                                                    .FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                            )
                                        )
                                        .build()
                                )
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                        val processor = RgbaAnalyzer(b.cameraOverlay) { front }
                        analyzer = processor
                        analysis = a
                        a.setAnalyzer(executor, processor)
                        capture = c
                        video = null
                        camera =
                            p.bindToLifecycle(
                                viewLifecycleOwner,
                                selector,
                                UseCaseGroup.Builder()
                                    .setViewPort(viewport)
                                    .addUseCase(preview)
                                    .addUseCase(c)
                                    .addUseCase(a)
                                    .build(),
                            )
                        b.cameraOverlay.visibility = View.VISIBLE
                    }
                    camera?.cameraControl?.setZoomRatio(1f)
                    b.cameraError.isVisible = false
                    b.cameraOverlay.exposureAvailable =
                        camera?.cameraInfo?.exposureState?.isExposureCompensationSupported == true
                    b.cameraZoom.removeAllViews()
                    camera?.cameraInfo?.zoomState?.value?.let { z ->
                        listOf(.5f, 1f, 2f)
                            .filter { it >= z.minZoomRatio && it <= z.maxZoomRatio }
                            .forEach { zoom ->
                                b.cameraZoom.addView(
                                    TextView(requireContext()).apply {
                                        text =
                                            if (zoom == 1f) "1x" else if (zoom == .5f) ".5" else "2"
                                        tag = zoom
                                        textSize = 12f
                                        typeface =
                                            ResourcesCompat.getFont(
                                                requireContext(),
                                                R.font.be_vietnam_pro_bold,
                                            )
                                        gravity = Gravity.CENTER
                                        layoutParams =
                                            LinearLayout.LayoutParams(dp(32), dp(32))
                                        setOnClickListener {
                                            camera?.cameraControl?.setZoomRatio(zoom)
                                        }
                                    }
                                )
                            }
                    }
                    // Quan sát zoom thật: pinch cũng cập nhật pill, không chỉ khi bấm nhãn nhanh.
                    observedZoom?.removeObservers(viewLifecycleOwner)
                    observedZoom = camera?.cameraInfo?.zoomState
                    observedZoom?.observe(viewLifecycleOwner) { state ->
                        if (binding === b)
                            for (i in 0 until b.cameraZoom.childCount) {
                                val label = b.cameraZoom.getChildAt(i) as TextView
                                val selected =
                                    kotlin.math.abs((label.tag as Float) - state.zoomRatio) < .05f
                                label.isSelected = selected
                                label.setTextColor(
                                    if (selected) 0xFFFFD54F.toInt()
                                    else Color.WHITE
                                )
                                label.setBackgroundResource(
                                    if (selected) R.drawable.bg_m4_zoom_selected else 0
                                )
                            }
                    }
                    renderControls()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    error(R.string.m4_camera_error)
                }
            }
    }

    private fun renderControls() {
        binding?.let { b ->
            (b.cameraFilters.parent as View).isVisible = !videoMode
            b.cameraPhotoControls.isVisible = !videoMode
            b.cameraShutter.contentDescription =
                getString(if (videoMode) R.string.m4_video_hold_hint else R.string.m4_take_photo)
            b.cameraTimer.isVisible = !videoMode
            b.cameraRatio.isVisible = !videoMode
            // Khi video ẩn filter, đưa zoom xuống sát đáy thay vì chừa khoảng trống của khay ảnh.
            (b.cameraZoom.layoutParams as FrameLayout.LayoutParams).apply {
                val landscape =
                    resources.configuration.orientation ==
                        Configuration.ORIENTATION_LANDSCAPE
                bottomMargin =
                    if (videoMode) dp(8)
                    else
                        (b.cameraFilters.parent as View).layoutParams.height +
                            dp(if (landscape) 10 else 48)
                b.cameraZoom.layoutParams = this
            }
            b.cameraFlash.isVisible = camera?.cameraInfo?.hasFlashUnit() == true && !videoMode
            b.cameraFlash.setCompoundDrawablesWithIntrinsicBounds(
                when (flash) {
                    ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_media_flash_auto
                    ImageCapture.FLASH_MODE_ON -> R.drawable.ic_media_flash_on
                    else -> R.drawable.ic_media_flash_off
                },
                0,
                0,
                0,
            )
            b.cameraFlash.text = ""
            b.cameraFlash.contentDescription =
                getString(
                    when (flash) {
                        ImageCapture.FLASH_MODE_ON -> R.string.m4_flash_on
                        ImageCapture.FLASH_MODE_OFF -> R.string.m4_flash_off
                        else -> R.string.m4_flash_auto
                    }
                )
            val timerIcon =
                ContextCompat.getDrawable(
                        requireContext(),
                        R.drawable.ic_media_timer,
                    )
                    ?.apply { setBounds(0, 0, dp(20), dp(20)) }
            b.cameraTimer.setCompoundDrawables(timerIcon, null, null, null)
            b.cameraTimer.text =
                if (timer == 0) getString(R.string.m4_camera_timer_off_short) else "${timer}s"
            b.cameraTimer.contentDescription =
                if (timer == 0) getString(R.string.m4_timer_off) else b.cameraTimer.text
            b.cameraRatio.text = listOf("4:3", "16:9", "1:1")[ratio]
            // Mockup dùng chữ trắng opacity60% cho mode chưa chọn, không dùng màu xám pha tím.
            b.cameraPhotoMode.setTextColor(
                if (!videoMode) 0xFFFFD54F.toInt() else Color.WHITE
            )
            b.cameraVideoMode.setTextColor(
                if (videoMode) 0xFFFFD54F.toInt() else Color.WHITE
            )
            b.cameraPhotoMode.alpha = if (!videoMode) 1f else .6f
            b.cameraVideoMode.alpha = if (videoMode) 1f else .6f
        }
    }

    private fun error(message: Int) {
        binding?.cameraError?.apply {
            setText(message)
            isVisible = true
        }
    }

    private fun takePhoto() {
        val b = binding ?: return

        val c = capture ?: return
        if (captureBusy) return
        captureBusy = true
        val selectedFilter = filter

        val square = ratio == 2

        val mirror = front
        shutterJob =
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    for (second in timer downTo 1) {
                        b.cameraCountdown.text = second.toString()
                        tick?.play()
                        delay(1000)
                    }
                    b.cameraCountdown.text = ""
                    shutterSound?.play()
                    val file = File(requireContext().cacheDir, "capture-${UUID.randomUUID()}.jpg")
                    suspendCancellableCoroutine<Unit> { cont ->
                        c.takePicture(
                            ImageCapture.OutputFileOptions.Builder(file).build(),
                            ContextCompat.getMainExecutor(requireContext()),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                    if (cont.isActive) cont.resume(Unit) else file.delete()
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    file.delete()
                                    if (cont.isActive) cont.resumeWithException(exception)
                                }
                            },
                        )
                    }
                    try {
                        // Full capture, không dùng screenshot preview720p làm ảnh cuối. EXIF xử lý
                        // đúng một lần.
                        val bitmap =
                            OwnedImageFiles.decode(requireContext(), Uri.fromFile(file), 8192)
                        val output =
                            withContext(Dispatchers.Default) {
                                val size = if (square) minOf(bitmap.width, bitmap.height) else 0
                                val out =
                                    Bitmap.createBitmap(
                                        if (square) size else bitmap.width,
                                        if (square) size else bitmap.height,
                                        Bitmap.Config.ARGB_8888,
                                    )
                                val paint =
                                    Paint(Paint.FILTER_BITMAP_FLAG).apply {
                                        colorFilter =
                                            ColorMatrixColorFilter(
                                                PhotoFilters.matrix(selectedFilter)
                                            )
                                    }
                                Canvas(out).apply {
                                    if (mirror) {
                                        translate(out.width.toFloat(), 0f)
                                        scale(-1f, 1f)
                                    }
                                    drawBitmap(
                                        bitmap,
                                        if (square) -(bitmap.width - size) / 2f else 0f,
                                        if (square) -(bitmap.height - size) / 2f else 0f,
                                        paint,
                                    )
                                }
                                bitmap.recycle()
                                out
                            }
                        val owned =
                            try {
                                OwnedImageFiles.jpeg(requireContext(), output)
                            } finally {
                                output.recycle()
                            }
                        val uri =
                            FileProvider.getUriForFile(
                                    requireContext(),
                                    "${requireContext().packageName}.media",
                                    owned,
                                )
                                .toString()
                        parentFragmentManager.setFragmentResult(
                            "create_capture",
                            Bundle().apply {
                                putString("uri", uri)
                                putString("mime", "image/jpeg")
                            },
                        )
                    } finally {
                        file.delete()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    error(R.string.m4_capture_error)
                } finally {
                    captureBusy = false
                    binding?.cameraCountdown?.text = ""
                }
            }
    }

    private fun startVideo() {
        val v = video ?: return
        if (recording != null || captureBusy || videoStartJob?.isActive == true) return
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        val b = binding ?: return
        validRecording = true
        b.cameraError.isVisible = false
        val file =
            File(requireContext().filesDir, "create-media")
                .apply { mkdirs() }
                .let { File(it, "video-${UUID.randomUUID()}.mp4") }
        activeVideoFile = file
        videoStartJob =
            viewLifecycleOwner.lifecycleScope.launch {
                var handedToRecorder = false
                try {
                    delay(VIDEO_HOLD_THRESHOLD_MS)
                    // Chiếm cùng owner với voice/ambient/Recorder, callback cũ chỉ pause, không tự
                    // resume sau video.
                    check(
                        playbackAudio.acquire(
                            audioOwner,
                            {
                                binding?.root?.post {
                                    videoStartJob?.cancel()
                                    recording?.stop()
                                }
                            },
                            kind = AudioPlaybackKind.VIDEO,
                        )
                    )
                    // RecorderService serialize pause qua actor. Chờ nhả mic thật rồi mới mở
                    // encoder CameraX.
                    withTimeout(2500) {
                        RecordingService.state.first {
                            it.phase !in
                                listOf(
                                    RecordPhase
                                        .RECORDING,
                                    RecordPhase
                                        .PREPARING,
                                    RecordPhase.STOPPING,
                                )
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    try {
                        recording =
                            v.output
                                .prepareRecording(
                                    requireContext(),
                                    FileOutputOptions.Builder(file)
                                        .setDurationLimitMillis(60_000)
                                        .build(),
                                )
                                .withAudioEnabled()
                                .start(ContextCompat.getMainExecutor(requireContext())) { event ->
                                    when (event) {
                                        is VideoRecordEvent.Start -> {
                                            if (binding === b) {
                                                b.cameraRecordingProgress.setProgressCompat(
                                                    0,
                                                    false,
                                                )
                                                b.cameraRecordingProgress.isVisible = true
                                                b.cameraCountdown.text = "0"
                                            }
                                        }

                                        is VideoRecordEvent.Status -> {
                                            if (binding === b) {
                                                val elapsedMs =
                                                    event.recordingStats.recordedDurationNanos /
                                                        1_000_000
                                                b.cameraRecordingProgress.setProgressCompat(
                                                    elapsedMs.coerceAtMost(60_000).toInt(),
                                                    true,
                                                )
                                                b.cameraCountdown.text =
                                                    (elapsedMs / 1000).toString()
                                            }
                                        }

                                        is VideoRecordEvent.Finalize -> {
                                            if (binding === b) {
                                                b.cameraRecordingProgress.isVisible = false
                                                b.cameraCountdown.text = ""
                                            }
                                            recording?.close()
                                            recording = null
                                            activeVideoFile = null
                                            playbackAudio.release(audioOwner)
                                            val usable =
                                                validRecording &&
                                                    (event.error ==
                                                        VideoRecordEvent.Finalize.ERROR_NONE ||
                                                        event.error ==
                                                            VideoRecordEvent.Finalize
                                                                .ERROR_DURATION_LIMIT_REACHED) &&
                                                    file.length() > 0
                                            if (usable && binding === b) {
                                                val uri =
                                                    FileProvider.getUriForFile(
                                                            requireContext(),
                                                            "${requireContext().packageName}.media",
                                                            file,
                                                        )
                                                        .toString()
                                                parentFragmentManager.setFragmentResult(
                                                    "create_capture",
                                                    Bundle().apply {
                                                        putString("uri", uri)
                                                        putString("mime", "video/mp4")
                                                        putLong(
                                                            "duration",
                                                            event.recordingStats
                                                                .recordedDurationNanos / 1_000_000,
                                                        )
                                                    },
                                                )
                                            } else {
                                                file.delete()
                                                if (binding === b) {
                                                    error(
                                                        if (
                                                            event.error ==
                                                                VideoRecordEvent.Finalize
                                                                    .ERROR_NO_VALID_DATA
                                                        )
                                                            R.string.m4_video_hold_hint
                                                        else R.string.m4_video_capture_error
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                    } catch (_: SecurityException) {
                        file.delete()
                        error(R.string.m4_mic_permission)
                    } catch (_: Exception) {
                        file.delete()
                        error(R.string.m4_video_capture_error)
                    }
                    handedToRecorder = recording != null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    error(R.string.m4_audio_busy)
                } finally {
                    if (!handedToRecorder) {
                        playbackAudio.release(audioOwner)
                        file.delete()
                        activeVideoFile = null
                    }
                }
            }
    }

    override fun onStop() {
        shutterJob?.cancel()
        videoStartJob?.cancel()
        recording?.stop()
        super.onStop()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putBoolean("front", front)
        out.putBoolean("video", videoMode)
        out.putInt("ratio", ratio)
        out.putInt("timer", timer)
        out.putInt("flash", flash)
        out.putInt("filter", filter.ordinal)
    }

    override fun onDestroyView() {
        validRecording = false
        videoStartJob?.cancel()
        recording?.stop()
        playbackAudio.release(audioOwner)
        bindJob?.cancel()
        shutterJob?.cancel()
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        analyzer?.close()
        binding?.cameraOverlay?.release()
        shutterSound?.close()
        tick?.close()
        executor.shutdown()
        executor = Executors.newSingleThreadExecutor()
        binding = null
        super.onDestroyView()
    }
}
