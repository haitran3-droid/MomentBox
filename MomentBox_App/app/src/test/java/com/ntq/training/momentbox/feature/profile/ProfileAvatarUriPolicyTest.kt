package com.ntq.training.momentbox.feature.profile

import android.provider.MediaStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileAvatarUriPolicyTest {
    private val packageName = "com.ntq.training.momentbox"

    @Test
    fun acceptsOwnedFileProviderAvatarUri() {
        assertTrue(
            ProfileViewModel.isAllowedAvatarUri(
                scheme = "content",
                authority = "$packageName.media",
                packageName = packageName
            )
        )
    }

    @Test
    fun acceptsMediaStoreExportedAvatarUri() {
        assertTrue(
            ProfileViewModel.isAllowedAvatarUri(
                scheme = "content",
                authority = MediaStore.AUTHORITY,
                packageName = packageName
            )
        )
    }

    @Test
    fun rejectsFileAndUnknownAuthorityUris() {
        assertFalse(
            ProfileViewModel.isAllowedAvatarUri(
                scheme = "file",
                authority = "",
                packageName = packageName
            )
        )
        assertFalse(
            ProfileViewModel.isAllowedAvatarUri(
                scheme = "content",
                authority = "com.example.other.provider",
                packageName = packageName
            )
        )
        assertFalse(
            ProfileViewModel.isAllowedAvatarUri(
                scheme = null,
                authority = MediaStore.AUTHORITY,
                packageName = packageName
            )
        )
    }
}
