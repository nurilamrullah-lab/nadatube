package com.nadatube.desktop.data

import com.nadatube.app.extractor.StreamQuality
import com.nadatube.app.model.Track
import com.nadatube.app.ui.theme.ThemeMode
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopStoreTest {

    private fun newStore(): Pair<DesktopStore, File> {
        val dir = Files.createTempDirectory("nadatube-store").toFile()
        return DesktopStore(dir) to dir
    }

    @Test
    fun `defaults are system theme and best quality`() {
        val (store, dir) = newStore()
        try {
            assertEquals(ThemeMode.SYSTEM, store.themeMode())
            assertEquals(StreamQuality.BEST, store.quality())
            assertEquals(0.8f, store.volume(), 0.001f)
            assertTrue(store.history().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `settings survive a reload`() {
        val (store, dir) = newStore()
        try {
            store.setThemeMode(ThemeMode.DARK)
            store.setQuality(StreamQuality.DATA_SAVER)
            store.setVolume(0.5f)
            store.saveVisitorData("VD-test")
            val reloaded = DesktopStore(dir)
            assertEquals(ThemeMode.DARK, reloaded.themeMode())
            assertEquals(StreamQuality.DATA_SAVER, reloaded.quality())
            assertEquals(0.5f, reloaded.volume(), 0.001f)
            assertEquals("VD-test", reloaded.visitorData())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `history dedups and keeps newest first`() {
        val (store, dir) = newStore()
        try {
            val a = Track("aaa", "A", "Art")
            val b = Track("bbb", "B", "Art")
            store.recordHistory(a)
            store.recordHistory(b)
            store.recordHistory(a)
            assertEquals(listOf("aaa", "bbb"), store.history().map { it.videoId })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `like toggles`() {
        val (store, dir) = newStore()
        try {
            assertFalse(store.isLiked("aaa"))
            assertTrue(store.toggleLike("aaa"))
            assertTrue(store.isLiked("aaa"))
            assertFalse(store.toggleLike("aaa"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
