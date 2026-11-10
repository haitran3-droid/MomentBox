package com.ntq.training.momentbox.feature.create

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.gson.Gson
import com.ntq.training.momentbox.databinding.ActivityCreateBinding
import com.ntq.training.momentbox.feature.gallery.GalleryFragment

data class CreateInput(
    val imagesOnly: Boolean = false,
    val initial: List<SelectedMedia> = emptyList(),
    val start: String = "gallery",
    val maxItems: Int = 10,
)

/** Contract nội bộ: chỉ nhận kết quả OK có JSON đọc được, Cancel trả danh sách rỗng. */
class CreateMediaContract : ActivityResultContract<CreateInput, List<SelectedMedia>>() {
    override fun createIntent(context: Context, input: CreateInput) =
        Intent(context, CreateActivity::class.java)
            .putExtra("maxItems", input.maxItems)
            .putExtra("imagesOnly", input.imagesOnly)
            .putExtra("initial", Gson().toJson(input.initial))
            .putExtra("start", input.start)

    override fun parseResult(resultCode: Int, intent: Intent?): List<SelectedMedia> =
        if (resultCode != Activity.RESULT_OK) emptyList()
        else
            runCatching {
                    Gson()
                        .fromJson(
                            intent?.getStringExtra("media") ?: "[]",
                            Array<SelectedMedia>::class.java,
                        )
                        .toList()
                }
                .getOrDefault(emptyList())
}

/** Host standard, các bước trao đổi qua Fragment Result thay vì nhiều Activity. */
class CreateActivity : AppCompatActivity() {
    val model: CreateViewModel by viewModels()
    private var navigationPending = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        enableEdgeToEdge()
        val binding = ActivityCreateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
                )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        model.configure(
            intent.getBooleanExtra("imagesOnly", false),
            intent.getIntExtra("maxItems", 10),
        )
        if (state == null) {
            model.seed(
                runCatching {
                        Gson()
                            .fromJson(
                                intent.getStringExtra("initial") ?: "[]",
                                Array<SelectedMedia>::class.java,
                            )
                            .toList()
                    }
                    .getOrDefault(emptyList())
            )
            supportFragmentManager
                .beginTransaction()
                .replace(
                    binding.root.id,
                    when (intent.getStringExtra("start")) {
                        "text" -> com.ntq.training.momentbox.feature.compose.ComposeFragment()
                        "camera" -> com.ntq.training.momentbox.feature.camera.CameraFragment()
                        else -> GalleryFragment()
                    },
                    if (intent.getStringExtra("start") == "text") "compose" else "gallery",
                )
                .commit()
        }
        // Màu vùng padding hệ thống theo màn đang hiển thị, không thay custom Splash của M1.
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentResumed(
                    manager: androidx.fragment.app.FragmentManager,
                    fragment: androidx.fragment.app.Fragment,
                ) {
                    navigationPending = false
                    val dark =
                        fragment is com.ntq.training.momentbox.feature.camera.CameraFragment ||
                            fragment is com.ntq.training.momentbox.feature.crop.CropFragment ||
                            fragment is
                                com.ntq.training.momentbox.feature.editor.PhotoEditorFragment
                    binding.root.setBackgroundColor(
                        if (dark) 0xFF0E0E12.toInt()
                        else getColor(com.ntq.training.momentbox.R.color.surface)
                    )
                    androidx.core.view.WindowCompat.getInsetsController(window, binding.root)
                        .apply {
                            isAppearanceLightStatusBars = !dark
                            isAppearanceLightNavigationBars = !dark
                        }
                }
            },
            false,
        )
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (
                        model.hasAddBackup &&
                            supportFragmentManager.findFragmentById(binding.root.id) is
                                GalleryFragment
                    ) {
                        model.cancelAdd()
                        supportFragmentManager.popBackStack(
                            "add-media",
                            androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE,
                        )
                    } else {
                        isEnabled = false
                        try {
                            onBackPressedDispatcher.onBackPressed()
                        } finally {
                            isEnabled = true
                        }
                    }
                }
            },
        )
        supportFragmentManager.setFragmentResultListener("create_add_media", this) { _, _ ->
            if (model.hasAddBackup || navigationPending) return@setFragmentResultListener
            navigationPending = true
            model.beginAdd()
            supportFragmentManager
                .beginTransaction()
                .replace(binding.root.id, GalleryFragment())
                .addToBackStack("add-media")
                .commit()
        }
        supportFragmentManager.setFragmentResultListener("create_return_gallery", this) { _, _ ->
            if (supportFragmentManager.backStackEntryCount > 0)
                supportFragmentManager.popBackStack()
            else
                supportFragmentManager
                    .beginTransaction()
                    .replace(binding.root.id, GalleryFragment(), "gallery")
                    .commit()
        }
        supportFragmentManager.setFragmentResultListener("create_camera", this) { _, _ ->
            if (navigationPending) return@setFragmentResultListener
            if (model.items.value.size >= model.maxItems) {
                android.widget.Toast.makeText(
                        this,
                        getString(
                            com.ntq.training.momentbox.R.string.m4_limit_remaining,
                            model.maxItems,
                        ),
                        android.widget.Toast.LENGTH_SHORT,
                    )
                    .show()
                return@setFragmentResultListener
            }
            navigationPending = true
            supportFragmentManager
                .beginTransaction()
                .replace(
                    binding.root.id,
                    com.ntq.training.momentbox.feature.camera.CameraFragment(),
                )
                .addToBackStack("camera")
                .commit()
        }
        supportFragmentManager.setFragmentResultListener("create_capture", this) { _, result ->
            val uri = result.getString("uri") ?: return@setFragmentResultListener
            val mime = result.getString("mime") ?: return@setFragmentResultListener
            val change =
                model.toggle(
                    SelectedMedia(
                        uri,
                        uri,
                        mime,
                        if (mime.startsWith("video/")) result.getLong("duration") else null,
                    )
                )
            if (change == SelectionChange.ADDED) {
                supportFragmentManager.setFragmentResult("gallery_reset", Bundle())
                if (supportFragmentManager.backStackEntryCount > 0)
                    supportFragmentManager.popBackStack()
                else
                    supportFragmentManager
                        .beginTransaction()
                        .replace(binding.root.id, GalleryFragment(), "gallery")
                        .commit()
            }
        }
        supportFragmentManager.setFragmentResultListener("create_selection", this) { _, _ ->
            model.submitted()
            nextPhoto()
        }
        supportFragmentManager.setFragmentResultListener("create_cropped", this) { _, result ->
            supportFragmentManager
                .beginTransaction()
                .replace(
                    binding.root.id,
                    com.ntq.training.momentbox.feature.editor.PhotoEditorFragment().apply {
                        arguments = Bundle(result)
                    },
                )
                .addToBackStack("editor")
                .commit()
        }
        supportFragmentManager.setFragmentResultListener("create_edited", this) { _, result ->
            val id = result.getString("id") ?: return@setFragmentResultListener
            val uri = result.getString("uri") ?: return@setFragmentResultListener
            model.edited(id, uri)
            supportFragmentManager.popBackStack(
                "crop",
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE,
            )
            binding.root.post { nextPhoto() }
        }
    }

    private fun nextPhoto() {
        val item = model.nextPhoto()
        if (item != null)
            supportFragmentManager
                .beginTransaction()
                .replace(
                    com.ntq.training.momentbox.R.id.create_host,
                    com.ntq.training.momentbox.feature.crop.CropFragment().apply {
                        arguments =
                            Bundle().apply {
                                putString("uri", item.uri)
                                putString("id", item.id)
                            }
                    },
                )
                .addToBackStack("crop")
                .commit()
        else if (model.imagesOnly) {
            setResult(RESULT_OK, Intent().putExtra("media", Gson().toJson(model.items.value)))
            finish()
        } else if (
            (0 until supportFragmentManager.backStackEntryCount).any {
                supportFragmentManager.getBackStackEntryAt(it).name == "add-media"
            }
        ) {
            model.completeAdd()
            supportFragmentManager.popBackStack(
                "add-media",
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE,
            )
        } else
            supportFragmentManager
                .beginTransaction()
                .replace(
                    com.ntq.training.momentbox.R.id.create_host,
                    com.ntq.training.momentbox.feature.compose.ComposeFragment(),
                    "compose",
                )
                .addToBackStack("compose")
                .commit()
    }
}
