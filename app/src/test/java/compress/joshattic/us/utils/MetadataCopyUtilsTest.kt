package compress.joshattic.us.utils

import com.coremedia.iso.IsoFile
import com.coremedia.iso.boxes.ChunkOffsetBox
import com.coremedia.iso.boxes.Container
import com.coremedia.iso.boxes.UserDataBox
import com.googlecode.mp4parser.FileDataSourceImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MetadataCopyUtilsTest {

    private fun ffmpegAvailable(): Boolean = try {
        val p = ProcessBuilder("which", "ffmpeg").start()
        p.waitFor() == 0
    } catch (e: Exception) {
        false
    }

    private fun ffmpeg(vararg args: String, workdir: File): Boolean {
        val cmd = mutableListOf("ffmpeg", "-y", "-loglevel", "error")
        cmd.addAll(args)
        val p = ProcessBuilder(cmd).directory(workdir).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        return p.waitFor() == 0
    }

    /** Builds a video. `faststart` forces `moov` before `mdat` (Media3 layout). */
    private fun makeVideo(dir: File, name: String, faststart: Boolean): File {
        val f = File(dir, name)
        val args = mutableListOf(
            "-f", "lavfi", "-i", "testsrc=size=160x120:rate=24:duration=1",
            "-metadata", "title=MyTestTitle",
            "-metadata", "com.apple.quicktime.location.ISO6709=+47.6062-122.3321/",
            "-metadata", "creation_time=2020-06-15T12:00:00.000000Z",
        )
        if (faststart) args.add("-movflags")
        if (faststart) args.add("+faststart")
        args.addAll(listOf("-pix_fmt", "yuv420p", "-c:v", "libx264", f.absolutePath))
        assertTrue("ffmpeg should create $name", ffmpeg(*args.toTypedArray(), workdir = dir))
        return f
    }

    private fun collectChunkOffsetBoxes(container: Container, out: MutableList<ChunkOffsetBox>) {
        for (box in container.boxes) {
            when (box) {
                is ChunkOffsetBox -> out.add(box)
                is Container -> collectChunkOffsetBoxes(box, out)
            }
        }
    }

    /** Byte offset at which `mdat` starts. */
    private fun mdatOffset(iso: IsoFile): Long {
        var off = 0L
        for (box in iso.boxes) {
            if (box.type == "mdat") return off
            off += box.size
        }
        return -1L
    }

    /** Full decode via ffmpeg; returns empty string on success, else error output. */
    private fun decodeErrors(file: File): String {
        val cmd = listOf("ffmpeg", "-v", "error", "-i", file.absolutePath, "-f", "null", "-")
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        return out
    }

    private fun assertMetadataCopiedCorrectly(src: File, dest: File) {
        val copied = MetadataCopyUtils.copyMetadataBetweenFiles(src, dest)
        assertTrue("copyMetadataBetweenFiles should succeed", copied)

        IsoFile(FileDataSourceImpl(dest)).use { after ->
            val movie = after.movieBox
            assertTrue("output should remain a valid MP4", movie != null)
            val udta = movie!!.getBoxes(UserDataBox::class.java)
            assertTrue("dest should now have a user-data block", udta.isNotEmpty())

            // The source's user-data block should fully replace dest's (empty) one.
            IsoFile(FileDataSourceImpl(src)).use { srcIso ->
                val srcUdta = srcIso.movieBox!!.getBoxes(UserDataBox::class.java)
                assertTrue(srcUdta.isNotEmpty())
                assertEquals("dest udta should equal source udta size", srcUdta[0].size, udta[0].size)

                // creation_time copied from the source.
                assertEquals(
                    srcIso.movieBox!!.movieHeaderBox!!.creationTime,
                    movie.movieHeaderBox!!.creationTime
                )
            }

            // Every chunk offset must point inside mdat (i.e. >= mdat start). A shift
            // caused by moov growing would put them before mdat.
            val mdatStart = mdatOffset(after)
            assertTrue("mdat must exist", mdatStart >= 0L)
            val chunkBoxes = mutableListOf<ChunkOffsetBox>()
            collectChunkOffsetBoxes(movie, chunkBoxes)
            assertTrue("moov should contain chunk offsets", chunkBoxes.isNotEmpty())
            chunkBoxes.forEach { box ->
                box.chunkOffsets.forEach { off ->
                    assertTrue("chunk offset $off should be within mdat (>= $mdatStart)", off >= mdatStart)
                }
            }
        }

        val decodeErrors = decodeErrors(dest)
        assertTrue("dest should decode cleanly, got: $decodeErrors", decodeErrors.isBlank())
    }

    @Test
    fun `copy metadata keeps moov after mdat layout intact`() {
        if (!ffmpegAvailable()) return
        val dir = createTempDir()
        try {
            val src = makeVideo(dir, "src.mp4", faststart = false)
            val dest = makeVideo(dir, "dest.mp4", faststart = false)
            assertMetadataCopiedCorrectly(src, dest)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `copy metadata corrects chunk offsets when moov precedes mdat (Media3 layout)`() {
        if (!ffmpegAvailable()) return
        val dir = createTempDir()
        try {
            val src = makeVideo(dir, "src.mp4", faststart = true)
            val dest = makeVideo(dir, "dest.mp4", faststart = true)
            assertMetadataCopiedCorrectly(src, dest)
        } finally {
            dir.deleteRecursively()
        }
    }
}
