package com.ntq.training.momentbox.feature.profile

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class ProfileLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (intent.action == Intent.ACTION_VIEW && uri?.scheme == "momentbox" &&
            uri.encodedAuthority == "user" && uri.encodedQuery == null && uri.encodedFragment == null
        ) {
            val id = uri.encodedPath?.removePrefix("/")?.takeIf { candidate ->
                candidate.isNotEmpty() && candidate.length <= 19 &&
                        candidate.first() in '1'..'9' && candidate.all { it in '0'..'9' }
            }?.toLongOrNull()
            if (id != null) startActivity(ProfileActivity.intent(this, id))
        }
        // Không chuyển tiếp extras, flags hoặc URI của caller. ProfileActivity kiểm phiên trước khi tải.
        finish()
    }
}