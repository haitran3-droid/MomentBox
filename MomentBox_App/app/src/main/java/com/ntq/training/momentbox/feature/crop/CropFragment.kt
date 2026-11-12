package com.ntq.training.momentbox.feature.crop

import kotlin.math.abs
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.media.OwnedImageFiles
import com.ntq.training.momentbox.databinding.FragmentCropBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Decode/export theo lifecycle, nguồn không bị xóa khi Cancel hoặc IO lỗi. */
class CropFragment : Fragment(R.layout.fragment_crop) {
    private var binding: FragmentCropBinding? = null
    private var selectedRatio = 0

    private fun degreesText(value: Int) =
        if (value < 0) "−${abs(value)}°" else "$value°"

    private fun ratioIcon(
        context: Context,
        index: Int,
        dp: Float,
    ): Drawable? =
        ContextCompat.getDrawable(
                context,
                when (index) {
                    0 -> R.drawable.ic_m4_crop_free
                    1 -> R.drawable.ic_m4_crop_square
                    3 -> R.drawable.ic_m4_crop_16_9
                    else -> R.drawable.ic_m4_crop_portrait
                },
            )
            ?.mutate()
            ?.apply { setBounds(0, 0, (18 * dp).toInt(), (18 * dp).toInt()) }

    override fun onViewCreated(view: View, state: Bundle?) {
        val b = FragmentCropBinding.bind(view)
        binding = b
        b.cropDone.isEnabled = false
        b.cropClose.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        val values = listOf(0.0, 1.0, .8, 16.0 / 9, 3.0 / 4)

        val names = listOf(getString(R.string.m4_free), "1:1", "4:5", "16:9", "3:4")
        selectedRatio = state?.getInt("ratioIndex") ?: 0
        fun renderRatios() {
            for (index in 0 until b.cropRatios.childCount) {
                val chip = b.cropRatios.getChildAt(index) as TextView
                chip.isSelected = index == selectedRatio
                val color = if (chip.isSelected) 0xFF111111.toInt() else Color.WHITE
                chip.setTextColor(color)
                chip.compoundDrawables[0]?.setTint(color)
            }
        }
        values.forEachIndexed { i, ratio ->
            b.cropRatios.addView(
                TextView(requireContext()).apply {
                    text = names[i]
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    typeface =
                        ResourcesCompat.getFont(
                            context,
                            R.font.be_vietnam_pro_medium,
                        )
                    // Nền/label32dp ở đầu vùng chạm48dp, phần đệm dưới thuộc khoảng trống của
                    // mockup.
                    val dp = resources.displayMetrics.density
                    compoundDrawablePadding = (5 * dp).toInt()
                    setCompoundDrawables(ratioIcon(context, i, dp), null, null, null)
                    background =
                        InsetDrawable(
                            ContextCompat.getDrawable(
                                context,
                                R.drawable.bg_media_crop_chip,
                            ),
                            0,
                            0,
                            0,
                            (16 * dp).toInt(),
                        )
                    // InsetDrawable tự ghi padding khi gán background, đặt padding sau đó để chữ
                    // không sát góc bo.
                    setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), (16 * dp).toInt())
                    layoutParams =
                        LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                (48 * dp).toInt(),
                            )
                            .apply { marginEnd = (8 * dp).toInt() }
                    setOnClickListener {
                        selectedRatio = i
                        b.cropCanvas.setRatio(ratio, true)
                        renderRatios()
                    }
                }
            )
        }
        renderRatios()
        b.cropRotate.setOnClickListener { b.cropCanvas.rotateQuarter() }
        b.cropFlip.setOnClickListener { b.cropCanvas.flip() }
        b.cropReset.setOnClickListener {
            b.cropCanvas.reset()
            b.cropRuler.setDegrees(0)
            b.cropDegrees.text = "0°"
            selectedRatio = 0
            renderRatios()
        }
        b.cropRuler.onAngle = {
            b.cropCanvas.setAngle(it.toDouble())
            b.cropDegrees.text = degreesText(it)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val image =
                    OwnedImageFiles.decode(
                        requireContext(),
                        Uri.parse(requireArguments().getString("uri")),
                    )
                b.cropCanvas.setImage(image)
                b.cropRuler.setDegrees(b.cropCanvas.currentAngle.toInt())
                b.cropDegrees.text = degreesText(b.cropRuler.degrees)
                b.cropDone.isEnabled = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Snackbar.make(view, R.string.m4_capture_error, Snackbar.LENGTH_INDEFINITE).show()
            }
        }
        b.cropDone.setOnClickListener {
            if (b.cropCanvas.busy) return@setOnClickListener
            b.cropCanvas.busy = true
            b.cropDone.isEnabled = false
            val snapshot = b.cropCanvas.exportSnapshot()
            val task =
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        // Export lấy snapshot trong lượt Main, pixel work không block UI.
                        withContext(Dispatchers.Default) { snapshot.render() }

                        val image = snapshot.take()
                        val file =
                            try {
                                OwnedImageFiles.jpeg(requireContext(), image)
                            } finally {
                                image.recycle()
                            }
                        val uri =
                            FileProvider.getUriForFile(
                                requireContext(),
                                "${requireContext().packageName}.media",
                                file,
                            )
                        parentFragmentManager.setFragmentResult(
                            "create_cropped",
                            Bundle().apply {
                                putString("uri", uri.toString())
                                putString("id", requireArguments().getString("id"))
                            },
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        Snackbar.make(view, R.string.m4_capture_error, Snackbar.LENGTH_LONG).show()
                    } finally {
                        snapshot.close()
                        b.cropCanvas.busy = false
                        b.cropDone.isEnabled = true
                    }
                }
            task.invokeOnCompletion { snapshot.close() }
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("ratioIndex", selectedRatio)
    }

    override fun onDestroyView() {
        binding?.cropCanvas?.release()
        binding = null
        super.onDestroyView()
    }
}
