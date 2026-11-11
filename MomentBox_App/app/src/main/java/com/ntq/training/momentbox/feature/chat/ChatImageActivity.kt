package com.ntq.training.momentbox.feature.chat

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import java.io.File
import kotlinx.coroutines.launch

class ChatImageActivity : AppCompatActivity() {
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved);
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }; root.addView(image, FrameLayout.LayoutParams(-1, -1))
        root.addView(
            ImageButton(this).apply {
                setImageResource(R.drawable.ic_arrow_back); contentDescription =
                getString(R.string.m5_chat_back); setOnClickListener { finish() }
            },
            FrameLayout.LayoutParams(
                (48 * resources.displayMetrics.density).toInt(),
                (48 * resources.displayMetrics.density).toInt(),
                Gravity.TOP or Gravity.START
            )
        )
        setContentView(root)
        lifecycleScope.launch {
            val app = application as MomentBoxApp; app.chatRuntime.activate();
            val expected = app.chatRuntime.current()
            val id = intent.getStringExtra("localId")
            val message = if (expected != null && id != null) app.database.chatDao()
                .findLocal(expected.ownerId, expected.source, id) else null
            if (message == null || message.type != "image" || app.chatRuntime.current() != expected) {
                finish(); return@launch
            }
            Glide.with(image).load(
                message.mediaUrl ?: message.ownedPath?.let {
                    Uri.fromFile(
                        File(
                            it
                        )
                    ).toString()
                }).into(image)
        }
    }
}