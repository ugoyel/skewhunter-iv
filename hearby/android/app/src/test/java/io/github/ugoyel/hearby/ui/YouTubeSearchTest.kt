package io.github.ugoyel.hearby.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeSearchTest {
    @Test
    fun tappingAVideoInYouTubesOwnPagesPicksIt() {
        assertEquals("dQw4w9WgXcQ", pickedVideo("https://m.youtube.com/watch?v=dQw4w9WgXcQ&pp=ygUEcmljaw%3D%3D"))
        assertEquals("dQw4w9WgXcQ", pickedVideo("https://m.youtube.com/watch?list=RD1&v=dQw4w9WgXcQ"))
        assertEquals("abcdefghijk", pickedVideo("https://m.youtube.com/shorts/abcdefghijk"))
    }

    @Test
    fun searchingAndBrowsingStayInTheSearch() {
        assertNull(pickedVideo("https://m.youtube.com/"))
        assertNull(pickedVideo("https://m.youtube.com/results?search_query=bollywood+party&sp=EgIQAQ%3D%3D"))
        assertNull(pickedVideo("https://m.youtube.com/@T-Series/videos"))
        assertNull(pickedVideo(null))
    }
}
