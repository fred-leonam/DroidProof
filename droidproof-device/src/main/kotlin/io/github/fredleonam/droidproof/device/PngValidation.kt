package io.github.fredleonam.droidproof.device

import java.io.DataInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/** Bounded structure, CRC and decode checks. This says nothing about the content of the display. */
internal fun validPng(
    path: Path,
    byteLimit: Long,
): Boolean {
    val size = Files.size(path)
    if (size !in 57..byteLimit) return false
    val inflater = Inflater()
    try {
        DataInputStream(Files.newInputStream(path)).use { input ->
            val signature = ByteArray(8)
            input.readFully(signature)
            if (!signature.contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))) return false
            var consumed = 8L
            var chunks = 0
            var dataSeen = false
            val buffer = ByteArray(8192)
            val decoded = ByteArray(8192)
            var inflatedBytes = 0L
            var inflatedLimit = 0L
            while (consumed < size) {
                val length = input.readInt().toLong() and 0xffffffffL
                val type = ByteArray(4)
                input.readFully(type)
                val name = type.toString(Charsets.US_ASCII)
                if (length > size - consumed - 12 || ++chunks > 100000) return false
                if (chunks == 1 && (name != "IHDR" || length != 13L)) return false
                if (chunks > 1 && name == "IHDR") return false
                val crc = CRC32().apply { update(type) }
                var remaining = length
                while (remaining > 0) {
                    val count = minOf(remaining, buffer.size.toLong()).toInt()
                    input.readFully(buffer, 0, count)
                    crc.update(buffer, 0, count)
                    if (name == "IHDR") {
                        val header = ByteBuffer.wrap(buffer)
                        val width = header.int
                        val height = header.int
                        if (width <= 0 || height <= 0 || width.toLong() * height > 16777216) return false
                        inflatedLimit = width.toLong() * height * 8 + height * 8L
                    }
                    if (name == "IDAT") {
                        if (inflater.finished()) return false
                        inflater.setInput(buffer, 0, count)
                        while (!inflater.needsInput() && !inflater.finished()) {
                            val inflated = inflater.inflate(decoded)
                            inflatedBytes += inflated
                            if (inflatedBytes > inflatedLimit || inflater.needsDictionary()) return false
                            if (inflated == 0 && !inflater.needsInput() && !inflater.finished()) return false
                        }
                        if (inflater.finished() && inflater.remaining > 0) return false
                    }
                    remaining -= count
                }
                if (crc.value != (input.readInt().toLong() and 0xffffffffL)) return false
                consumed += length + 12
                if (name == "IDAT" && length > 0) dataSeen = true
                if (name == "IEND") {
                    if (length != 0L || consumed != size || !dataSeen || !inflater.finished()) return false
                    // Dimensions and compressed bytes were capped before allocating decoded pixels.
                    Files.newInputStream(path).use { stream ->
                        MemoryCacheImageInputStream(stream).use { imageInput ->
                            val reader = ImageIO.getImageReadersByFormatName("png").next()
                            try {
                                reader.setInput(imageInput, true, true)
                                return reader.read(0) != null
                            } finally {
                                reader.dispose()
                            }
                        }
                    }
                }
            }
        }
    } catch (_: EOFException) {
        return false
    } catch (_: javax.imageio.IIOException) {
        return false
    } catch (_: DataFormatException) {
        return false
    } finally {
        inflater.end()
    }
    return false
}
