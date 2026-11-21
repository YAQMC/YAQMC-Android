package org.yaqmc.android.nativeui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeArtworkTest {
    @Test fun homepageThumbnailsUseTheSmallestAdequateCoverAndLargeTilesKeepTheOriginal() {
        val artwork = JSONObject("""
            {"src":"cover-300","variants":[
                {"src":"cover-800","width":800,"height":800},
                {"src":"too-short","width":150,"height":100},
                {"src":"cover-150","width":150,"height":150},
                {"src":"cover-300","width":300,"height":300},
                {"src":"invalid","width":-1,"height":150}
            ]}
        """.trimIndent())
        val page = NativeModels.home(JSONObject()
            .put("guessSonglist", JSONObject().put("id", "guess").put("artwork", artwork))
            .put("radarSongs", org.json.JSONArray(listOf(JSONObject().put("id", "radar").put("artwork", artwork)))))

        val track = checkNotNull(page.tracks.single().artworkImage)
        val card = checkNotNull(page.sections.first().cards.single().artworkImage)
        assertEquals("cover-150", track.sourceFor(144, 144))
        assertEquals("cover-150", track.sourceFor(168, 168))
        assertEquals("cover-300", track.sourceFor(169, 168))
        assertEquals("cover-300", card.sourceFor(151, 150))
        assertEquals("cover-300", card.sourceFor(660, 400))
        assertEquals("cover-300", card.sourceFor(0, 0))
    }

    @Test fun singleSizeAndLegacyCoversRetainTheirOriginalUrl() {
        val page = NativeModels.home(JSONObject("""
            {"recommendedSonglists":[
                {"id":"single","artwork":{"src":"https://qpic.y.qq.com/cover.jpg"}},
                {"id":"legacy","cover":"https://qpic.y.qq.com/legacy.jpg"},
                {"id":"missing"}
            ]}
        """.trimIndent()))
        val (single, legacy, missing) = page.sections.single().cards
        assertEquals("https://qpic.y.qq.com/cover.jpg", single.artworkImage?.sourceFor(144, 144))
        assertEquals("https://qpic.y.qq.com/legacy.jpg", legacy.artworkImage?.sourceFor(144, 144))
        assertNull(missing.artworkImage)
    }
}
