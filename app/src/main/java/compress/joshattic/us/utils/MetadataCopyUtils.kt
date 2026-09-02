package compress.joshattic.us.utils

import android.content.Context
import android.net.Uri
import com.coremedia.iso.IsoFile
import com.coremedia.iso.boxes.ChunkOffsetBox
import com.coremedia.iso.boxes.Container
import com.coremedia.iso.boxes.UserDataBox
import com.googlecode.mp4parser.FileDataSourceImpl
import java.io.File
import java.io.FileOutputStream

/**
 * Copies metadata from an original video (source Uri) onto a freshly compressed
 * MP4 file. Media3's Transformer drops most of the original metadata, so after
 * compression we transplant the valuable boxes (creation/modification times and
 * the full `udta` block, which holds GPS, camera info, iTunes-style keys, XMP,
 * etc.) from the source onto the output.
 *
 * Only ISO-BMFF containers (MP4, MOV, M4V, 3GP) can be read. For any other
 * source format this returns `false` so the caller can warn the user.
 */
object MetadataCopyUtils {

    /**
     * @return `true` if metadata was successfully copied, `false` if it could not
     *         be copied (unsupported source, parse error, etc.). Never throws.
     */
    fun copyMetadata(context: Context, sourceUri: Uri, destFile: File): Boolean {
        if (!destFile.exists() || destFile.length() <= 0L) return false

        val cacheDir = context.cacheDir
        val srcTemp = File(cacheDir, "meta_src_${System.currentTimeMillis()}.tmp")
        try {
            if (!copySourceToTemp(context, sourceUri, srcTemp)) return false
            return copyMetadataBetweenFiles(srcTemp, destFile)
        } finally {
            srcTemp.delete()
        }
    }

    /** Copies metadata between two local MP4/MOV files. Exposed for testing. */
    fun copyMetadataBetweenFiles(srcFile: File, destFile: File): Boolean {
        if (!destFile.exists() || destFile.length() <= 0L) return false
        if (!srcFile.exists() || srcFile.length() <= 0L) return false

        val outTemp = File(destFile.parentFile, "meta_out_${System.currentTimeMillis()}.tmp")

        var srcIso: IsoFile? = null
        var dstIso: IsoFile? = null
        try {
            srcIso = IsoFile(FileDataSourceImpl(srcFile))
            dstIso = IsoFile(FileDataSourceImpl(destFile))

            val srcMovie = srcIso.movieBox
            val dstMovie = dstIso.movieBox
            if (srcMovie == null || dstMovie == null) return false

            // 1. Copy creation / modification times so the file date matches the original.
            srcMovie.movieHeaderBox?.let { srcMvhd ->
                dstMovie.movieHeaderBox?.let { dstMvhd ->
                    dstMvhd.creationTime = srcMvhd.creationTime
                    dstMvhd.modificationTime = srcMvhd.modificationTime
                }
            }

            // 2. Replace the output's user-data block with the source's. This block
            //    carries GPS, camera info, keys/ilst tags, XMP, etc.
            val srcUdta = srcMovie.getBoxes(UserDataBox::class.java)
            var mdatDelta = 0L
            if (srcUdta.isNotEmpty()) {
                val removedSize = dstMovie.getBoxes(UserDataBox::class.java).sumOf { it.size }
                val remaining = dstMovie.boxes.filterNot { it is UserDataBox }
                dstMovie.setBoxes(remaining)
                srcUdta.forEach { dstMovie.addBox(it) }
                mdatDelta = srcUdta.sumOf { it.size } - removedSize
            }

            // 3. Growing/shrinking moov shifts mdat when moov precedes mdat. Chunk
            //    offsets are absolute file positions, so they must be corrected,
            //    otherwise every sample is read from the wrong place and the video
            //    is unplayable.
            if (mdatDelta != 0L && moovPrecedesMdat(dstIso)) {
                val chunkBoxes = mutableListOf<ChunkOffsetBox>()
                collectChunkOffsetBoxes(dstMovie, chunkBoxes)
                chunkBoxes.forEach { box ->
                    box.chunkOffsets = box.chunkOffsets.map { it + mdatDelta }.toLongArray()
                }
            }

            // 4. Write the patched container to a temp file, then replace the original.
            FileOutputStream(outTemp).use { fos ->
                dstIso.getBox(fos.channel)
            }

            if (outTemp.exists() && outTemp.length() > 0L) {
                outTemp.copyTo(destFile, overwrite = true)
                return true
            }
            return false
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try { srcIso?.close() } catch (_: Exception) {}
            try { dstIso?.close() } catch (_: Exception) {}
            outTemp.delete()
        }
    }

    /** True when the `moov` box sits before `mdat` at the top level. */
    private fun moovPrecedesMdat(iso: IsoFile): Boolean {
        val types = iso.boxes.map { it.type }
        val moovIdx = types.indexOf("moov")
        val mdatIdx = types.indexOf("mdat")
        return moovIdx >= 0 && mdatIdx > moovIdx
    }

    /** Collects every [ChunkOffsetBox] (stco/co64) anywhere below a container. */
    private fun collectChunkOffsetBoxes(container: Container, out: MutableList<ChunkOffsetBox>) {
        for (box in container.boxes) {
            when (box) {
                is ChunkOffsetBox -> out.add(box)
                is Container -> collectChunkOffsetBoxes(box, out)
            }
        }
    }

    private fun copySourceToTemp(context: Context, uri: Uri, tempFile: File): Boolean {
        return try {
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            copied != null && tempFile.length() > 0L
        } catch (e: Exception) {
            e.printStackTrace()
            tempFile.delete()
            false
        }
    }
}
