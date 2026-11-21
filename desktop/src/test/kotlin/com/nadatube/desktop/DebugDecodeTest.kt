package com.nadatube.desktop

import com.nadatube.desktop.audio.AacDecoder
import com.nadatube.desktop.audio.Mp4Demuxer
import com.nadatube.desktop.audio.Pcm
import java.io.File
import kotlin.test.Test

class DebugDecodeTest {
    private fun run(label: String, bytes: ByteArray, limit: Int = 40) {
        val t = Mp4Demuxer.parse(bytes)
        val dec = AacDecoder(t.config)
        var silent = 0
        val sb = StringBuilder()
        for (i in t.samples.indices) {
            val r = Pcm.rms(dec.decode(t.frame(i)))
            if (r == 0.0) silent++
            if (i < limit) sb.append("%.2f ".format(r))
        }
        println("DBG $label cfg=${t.config.joinToString("") { "%02x".format(it) }} frames=${t.samples.size} silent=$silent first: $sb")
        // Independent decoders per frame: is the silence caused by decoder state?
        var silentFresh = 0
        for (i in 0 until minOf(t.samples.size, 60)) if (Pcm.rms(AacDecoder(t.config).decode(t.frame(i))) == 0.0) silentFresh++
        println("DBG $label fresh-decoder-per-frame silent=$silentFresh/60")
    }

    @Test
    fun debug() {
        val yt = File("/tmp/itag140.m4a")
        if (yt.exists()) run("youtube", yt.readBytes())
        run("ffmpeg-tone", DebugDecodeTest::class.java.getResourceAsStream("/audio/tone440_fragmented.m4a")!!.readBytes())
    }
}
