package com.shilapi.xcertplay.youtube

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoSessionSettings
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class YoutubePopupSessionFactoryTest {
    @Test
    fun returnedPopupSessionIsUnopened() {
        val settings = GeckoSessionSettings.Builder()
            .contextId("diplay_youtube_example")
            .build()

        val popup = YoutubePopupSessionFactory.create(settings)

        assertFalse(popup.isOpen)
    }
}
