package com.ntq.training.momentbox.feature.create

import androidx.lifecycle.SavedStateHandle
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.media.OwnedImageFiles
import com.ntq.training.momentbox.data.repository.UploadPhase
import com.ntq.training.momentbox.data.repository.UploadRepository
import com.ntq.training.momentbox.data.repository.UploadState
import com.ntq.training.momentbox.feature.camera.CameraFragment
import com.ntq.training.momentbox.feature.compose.ComposeFragment
import com.ntq.training.momentbox.feature.crop.CropOverlayView
import com.ntq.training.momentbox.feature.crop.CropRect
import com.ntq.training.momentbox.feature.journal.view.AudioWaveformView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateMediaRegressionTest {

    @Test
    fun pendingCameraCaptureRollbackAllowsRetakeAtMaxItemsOne() {
        val model = CreateViewModel(SavedStateHandle())
        model.configure(imageOnly = true, limit = 1)

        val first =
            SelectedMedia(
                id = "content://pkg.media/created/image-1.jpg",
                uri = "content://pkg.media/created/image-1.jpg",
                mimeType = "image/jpeg",
            )
        assertEquals(SelectionChange.ADDED, model.beginCameraCapture(first))
        assertEquals(1, model.items.value.size)
        assertEquals(first.id, model.pendingCameraCaptureId)

        // While unconfirmed, capacity is full; backing out of Crop rolls it back and returns URI.
        val abandonedUri = model.rollbackPendingCameraCapture()
        assertEquals(first.uri, abandonedUri)
        assertTrue(model.items.value.isEmpty())
        assertNull(model.pendingCameraCaptureId)
        assertFalse(model.hasSubmitted)

        // Retaking with maxItems = 1 now succeeds cleanly.
        val second =
            SelectedMedia(
                id = "content://pkg.media/created/image-2.jpg",
                uri = "content://pkg.media/created/image-2.jpg",
                mimeType = "image/jpeg",
            )
        assertEquals(SelectionChange.ADDED, model.beginCameraCapture(second))
        assertEquals(1, model.items.value.size)

        // Committing the edit clears pending capture so subsequent camera resume will not remove it.
        val replacedUri = model.commitCameraCapture(second.id)
        assertEquals(second.uri, replacedUri)
        model.edited(second.id, "content://media/external/images/media/42")
        assertNull(model.rollbackPendingCameraCapture())
        assertEquals(1, model.items.value.size)
        assertEquals("content://media/external/images/media/42", model.items.value.single().uri)
    }

    @Test
    fun uploadRepositoryBeginComposeClearsFailedCancelledAndSucceeded() {
        val failedRepo = UploadRepository.forTest(UploadState(UploadPhase.FAILED))
        try {
            failedRepo.beginCompose()
            assertEquals(UploadPhase.IDLE, failedRepo.state.value.phase)
        } finally {
            failedRepo.close()
        }

        val cancelledRepo = UploadRepository.forTest(UploadState(UploadPhase.CANCELLED))
        try {
            cancelledRepo.beginCompose()
            assertEquals(UploadPhase.IDLE, cancelledRepo.state.value.phase)
        } finally {
            cancelledRepo.close()
        }

        val succeededRepo = UploadRepository.forTest(UploadState(UploadPhase.SUCCEEDED))
        try {
            succeededRepo.beginCompose()
            assertEquals(UploadPhase.IDLE, succeededRepo.state.value.phase)
        } finally {
            succeededRepo.close()
        }

        // Pending journal retry state must NOT be wiped by beginCompose or clearTerminalFailure.
        val journalFailedRepo = UploadRepository.forTest(UploadState(UploadPhase.JOURNAL_FAILED))
        try {
            journalFailedRepo.beginCompose()
            assertEquals(UploadPhase.JOURNAL_FAILED, journalFailedRepo.state.value.phase)
            journalFailedRepo.clearTerminalFailure()
            assertEquals(UploadPhase.JOURNAL_FAILED, journalFailedRepo.state.value.phase)
        } finally {
            journalFailedRepo.close()
        }
    }

    @Test
    fun audioWaveformDownsampleUsesPeakPerBucketForLongRecordings() {
        assertTrue(AudioWaveformView.downsample(emptyList(), 80).isEmpty())
        assertTrue(AudioWaveformView.downsample(listOf(10, 20), 0).isEmpty())

        val shortSamples = listOf(10, 40, 25, 80, 15)
        assertEquals(shortSamples, AudioWaveformView.downsample(shortSamples, 80))

        // 6,000 samples (300s @ 20Hz) with known peaks in specific buckets.
        val longSamples = MutableList(6000) { 12 }
        longSamples[5] = 95 // bucket 0 (0..59)
        longSamples[3010] = 88 // bucket 50 (3000..3059)
        longSamples[5999] = 100 // bucket 99 (5940..5999)

        val downsampled = AudioWaveformView.downsample(longSamples, maxBars = 100)
        assertEquals(100, downsampled.size)
        assertEquals(95, downsampled[0])
        assertEquals(88, downsampled[50])
        assertEquals(100, downsampled[99])
        assertEquals(12, downsampled[1])
    }

    @Test
    fun cropLockedAspectRatioEdgeDragsResizeSmoothlyAndPreserveRatio() {
        val initial = CropRect(200.0, 200.0, 600.0, 600.0) // 400x400 (1:1) inside 1000x1000

        // Drag right edge outward by +100px -> width becomes 500, height grows symmetrically to 500.
        val rightExpanded =
            CropOverlayView.resizeCropRect(
                current = initial,
                edges = 2,
                dx = 100.0,
                dy = 0.0,
                viewWidth = 1000.0,
                viewHeight = 1000.0,
                ratio = 1.0,
                minSidePx = 48.0,
            )
        assertEquals(200.0, rightExpanded.left, 1e-6)
        assertEquals(700.0, rightExpanded.right, 1e-6)
        assertEquals(500.0, rightExpanded.width, 1e-6)
        assertEquals(500.0, rightExpanded.height, 1e-6)
        assertEquals(400.0, rightExpanded.cy, 1e-6)

        // Drag bottom edge outward by +100px -> height becomes 500, width grows symmetrically to 500.
        val bottomExpanded =
            CropOverlayView.resizeCropRect(
                current = initial,
                edges = 8,
                dx = 0.0,
                dy = 100.0,
                viewWidth = 1000.0,
                viewHeight = 1000.0,
                ratio = 1.0,
                minSidePx = 48.0,
            )
        assertEquals(200.0, bottomExpanded.top, 1e-6)
        assertEquals(700.0, bottomExpanded.bottom, 1e-6)
        assertEquals(500.0, bottomExpanded.width, 1e-6)
        assertEquals(500.0, bottomExpanded.height, 1e-6)
        assertEquals(400.0, bottomExpanded.cx, 1e-6)

        // Drag left edge outward by -100px -> right edge stays anchored at 600, box does NOT slide.
        val leftExpanded =
            CropOverlayView.resizeCropRect(
                current = initial,
                edges = 1,
                dx = -100.0,
                dy = 0.0,
                viewWidth = 1000.0,
                viewHeight = 1000.0,
                ratio = 1.0,
                minSidePx = 48.0,
            )
        assertEquals(100.0, leftExpanded.left, 1e-6)
        assertEquals(600.0, leftExpanded.right, 1e-6)
        assertEquals(500.0, leftExpanded.height, 1e-6)

        // Drag top edge outward by -100px -> bottom edge stays anchored at 600.
        val topExpanded =
            CropOverlayView.resizeCropRect(
                current = initial,
                edges = 4,
                dx = 0.0,
                dy = -100.0,
                viewWidth = 1000.0,
                viewHeight = 1000.0,
                ratio = 1.0,
                minSidePx = 48.0,
            )
        assertEquals(100.0, topExpanded.top, 1e-6)
        assertEquals(600.0, topExpanded.bottom, 1e-6)
        assertEquals(500.0, topExpanded.width, 1e-6)
    }

    @Test
    fun ownedImageFilesSampleSizeCapsAt2048AndVideoDurationLimitNormalizes() {
        // High-MP sensor (8000x6000) requesting 8192 is still capped to <= 2048px.
        val sample = OwnedImageFiles.computeSampleSize(8000, 6000, requestedMaxSide = 8192)
        assertTrue(8000 / sample <= OwnedImageFiles.MAX_DECODE_SIDE)
        assertEquals(4, sample)

        // 60s CameraX duration limit overshoot (e.g. 60,033ms) normalizes cleanly into 1..60_000.
        assertEquals(
            60_000L,
            CameraFragment.normalizeCapturedVideoDuration(60_033L, hitDurationLimit = true),
        )
        assertEquals(
            60_000L,
            CameraFragment.normalizeCapturedVideoDuration(60_120L, hitDurationLimit = false),
        )
        assertEquals(
            15_250L,
            CameraFragment.normalizeCapturedVideoDuration(15_250L, hitDurationLimit = false),
        )
    }

    @Test
    fun composePrivacyPresentationMapsIconAndLabelForAllPrivacyModes() {
        assertEquals(
            R.drawable.ic_m4_public to R.string.m4_public,
            ComposeFragment.privacyPresentation("public"),
        )
        assertEquals(
            R.drawable.ic_group to R.string.m4_friends,
            ComposeFragment.privacyPresentation("friends"),
        )
        assertEquals(
            R.drawable.ic_lock to R.string.m4_private,
            ComposeFragment.privacyPresentation("private"),
        )
        assertEquals(
            R.drawable.ic_m4_public to R.string.m4_public,
            ComposeFragment.privacyPresentation("unknown"),
        )
    }
}
