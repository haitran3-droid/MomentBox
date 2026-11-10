package com.ntq.training.momentbox.feature.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.view.*
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.core.resolutionselector.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.media.*
import com.ntq.training.momentbox.databinding.FragmentCameraBinding
import com.ntq.training.momentbox.feature.create.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** CameraX gắn với viewLifecycleOwner. File chỉ trả sau capture/finalize thành công. */
class CameraFragment : Fragment(R.layout.fragment_camera) {
    private var binding: FragmentCameraBinding? = null
    private val create: CreateViewModel by activityViewModels()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
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
    private var lastUri: String? = null
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
        b.cameraPhotoMode.setOnClickListener { changeMode(false) }
        b.cameraVideoMode.isVisible = !create.imagesOnly
        b.cameraVideoMode.setOnClickListener { changeMode(true) }
        lastUri = create.items.value.lastOrNull { !it.isVideo }?.uri
        lastUri?.let {
            com.bumptech.glide.Glide.with(this).load(it).centerCrop().into(b.cameraLatest)
        }
        b.cameraLatest.setOnClickListener {
            parentFragmentManager.setFragmentResult("create_return_gallery", Bundle())
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
        val thumbnails = mutableListOf<com.google.android.material.imageview.ShapeableImageView>()
        val labels = mutableListOf<TextView>()
        PhotoFilter.entries.forEachIndexed { i, f ->
            val column =
                android.widget.LinearLayout(requireContext()).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams =
                        android.widget.LinearLayout.LayoutParams(
                            dp(66),
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                }
            val image =
                com.google.android.material.imageview.ShapeableImageView(requireContext()).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp(54), dp(54))
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    shapeAppearanceModel =
                        shapeAppearanceModel.toBuilder().setAllCornerSizes(dp(27).toFloat()).build()
                    strokeColor =
                        android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                    strokeWidth = if (f == filter) dp(2).toFloat() else 0f
                    colorFilter = ColorMatrixColorFilter(PhotoFilters.matrix(f))
                }
            val label =
                TextView(requireContext()).apply {
                    text = getString(names[i])
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    typeface =
                        androidx.core.content.res.ResourcesCompat.getFont(
                            requireContext(),
                            R.font.be_vietnam_pro_regular,
                        )
                }
            column.addView(image)
            column.addView(label)
            b.cameraFilters.addView(column)
            thumbnails.add(image)
            labels.add(label)
            column.setOnClickListener {
                filter = f
                b.cameraOverlay.filter = f
                thumbnails.forEachIndexed { n, v ->
                    v.strokeWidth = if (n == i) dp(2).toFloat() else 0f
                }
                labels.forEachIndexed { n, v ->
                    v.typeface =
                        androidx.core.content.res.ResourcesCompat.getFont(
                            requireContext(),
                            if (n == i) R.font.be_vietnam_pro_bold
                            else R.font.be_vietnam_pro_regular,
                        )
                }
            }
        }
        b.cameraOverlay.onThumbnail = { bitmap ->
            if (binding === b) thumbnails.forEach { it.setImageBitmap(bitmap) }
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
        var downY = 0f

        var downX = 0f

        var multiple = false
        b.cameraOverlay.setOnTouchListener { _, event ->
            scale.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.y
                    downX = event.x
                    multiple = false
                }

                MotionEvent.ACTION_POINTER_DOWN -> multiple = true
                MotionEvent.ACTION_MOVE ->
                    if (!multiple && kotlin.math.abs(event.y - downY) > dp(12)) {
                        camera?.let { c ->
                            val e = c.cameraInfo.exposureState
                            if (e.isExposureCompensationSupported)
                                c.cameraControl.setExposureCompensationIndex(
                                    (e.exposureCompensationIndex + (downY - event.y) / dp(30))
                                        .toInt()
                                        .coerceIn(
                                            e.exposureCompensationRange.lower,
                                            e.exposureCompensationRange.upper,
                                        )
                                )
                        }
                        downY = event.y
                    }

                MotionEvent.ACTION_UP ->
                    if (
                        !multiple &&
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
                        b.cameraOverlay.focusX = event.x
                        b.cameraOverlay.focusY = event.y
                        b.cameraOverlay.invalidate()
                        b.cameraOverlay.postDelayed(
                            {
                                if (binding === b) {
                                    b.cameraOverlay.focusX = -1f
                                    b.cameraOverlay.invalidate()
                                }
                            },
                            2000,
                        )
                    }
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

    private fun changeMode(video: Boolean) {
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
                        b.cameraPreview.display?.rotation ?: android.view.Surface.ROTATION_0
                    val landscape =
                        resources.configuration.orientation ==
                            android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    val viewportAspect =
                        if (ratio == 2 && !videoMode) android.util.Rational(1, 1)
                        else if (wide) {
                            if (landscape) android.util.Rational(16, 9)
                            else android.util.Rational(9, 16)
                        } else {
                            if (landscape) android.util.Rational(4, 3)
                            else android.util.Rational(3, 4)
                        }
                    val viewport =
                        ViewPort.Builder(viewportAspect, rotation)
                            .setScaleType(ViewPort.FILL_CENTER)
                            .build()
                    b.cameraPreview.scaleType =
                        androidx.camera.view.PreviewView.ScaleType.FIT_CENTER
                    b.cameraOverlay.viewportRatio = viewportAspect.toFloat()
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
                    b.cameraZoom.removeAllViews()
                    camera?.cameraInfo?.zoomState?.value?.let { z ->
                        listOf(.5f, 1f, 2f)
                            .filter { it >= z.minZoomRatio && it <= z.maxZoomRatio }
                            .forEach { zoom ->
                                b.cameraZoom.addView(
                                    TextView(requireContext()).apply {
                                        text = if (zoom == 1f) "1x" else zoom.toString()
                                        setTextColor(0xFFFFD54F.toInt())
                                        gravity = Gravity.CENTER
                                        layoutParams =
                                            android.widget.LinearLayout.LayoutParams(dp(40), dp(40))
                                        setOnClickListener {
                                            camera?.cameraControl?.setZoomRatio(zoom)
                                        }
                                    }
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
            b.cameraTimer.isVisible = !videoMode
            b.cameraRatio.isVisible = !videoMode
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
            b.cameraTimer.setCompoundDrawablesWithIntrinsicBounds(
                R.drawable.ic_media_timer,
                0,
                0,
                0,
            )
            b.cameraTimer.text = if (timer == 0) getString(R.string.m4_timer_off) else "${timer}s"
            b.cameraRatio.text = listOf("4:3", "16:9", "1:1")[ratio]
            b.cameraPhotoMode.setTextColor(
                if (!videoMode) 0xFFFFD54F.toInt() else android.graphics.Color.WHITE
            )
            b.cameraVideoMode.setTextColor(
                if (videoMode) 0xFFFFD54F.toInt() else android.graphics.Color.WHITE
            )
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
                        lastUri = uri
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
        val file =
            File(requireContext().filesDir, "create-media")
                .apply { mkdirs() }
                .let { File(it, "video-${UUID.randomUUID()}.mp4") }
        activeVideoFile = file
        videoStartJob =
            viewLifecycleOwner.lifecycleScope.launch {
                var handedToRecorder = false
                try {
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
                        com.ntq.training.momentbox.feature.recorder.RecordingService.state.first {
                            it.phase !in
                                listOf(
                                    com.ntq.training.momentbox.feature.recorder.RecordPhase
                                        .RECORDING,
                                    com.ntq.training.momentbox.feature.recorder.RecordPhase
                                        .PREPARING,
                                    com.ntq.training.momentbox.feature.recorder.RecordPhase.STOPPING,
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
                                        is VideoRecordEvent.Status ->
                                            if (binding === b)
                                                b.cameraCountdown.text =
                                                    (event.recordingStats.recordedDurationNanos /
                                                            1_000_000_000)
                                                        .toString()

                                        is VideoRecordEvent.Finalize -> {
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
                                                if (binding === b) error(R.string.m4_capture_error)
                                            }
                                            binding?.cameraCountdown?.text = ""
                                        }
                                    }
                                }
                    } catch (_: SecurityException) {
                        file.delete()
                        error(R.string.m4_mic_permission)
                    } catch (_: Exception) {
                        file.delete()
                        error(R.string.m4_capture_error)
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
