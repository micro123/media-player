package com.tang.player.data

import org.junit.Assert.*
import org.junit.Test

class VideoOrientationTest {
    @Test fun freshPreferencesDefaultToLandscape() {
        assertEquals(VideoOrientation.LANDSCAPE, PlayerPreferences().orientation)
        assertEquals(VideoOrientation.LANDSCAPE, VideoOrientation.fromStored(null))
    }
    @Test fun legacyAutoAndInvalidSettingsUseNewDefault() {
        assertEquals(VideoOrientation.LANDSCAPE, VideoOrientation.fromStored("AUTO"))
        assertEquals(VideoOrientation.LANDSCAPE, VideoOrientation.fromStored("invalid"))
    }
    @Test fun explicitPortraitLandscapeAndKeepSelectionsArePreserved() {
        VideoOrientation.entries.forEach { assertEquals(it, VideoOrientation.fromStored(it.name)) }
    }
}
