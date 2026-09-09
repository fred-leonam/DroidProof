package io.github.fredleonam.droidproof.device

import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PngValidationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `compressed truncation is rejected even with valid chunk checksums and IEND`() {
        val png = png()
        val truncated = rewrite(png) { name, data -> if (name == "IDAT") data.copyOf(data.size - 1) else data }
        assertFalse(valid(truncated))
    }

    @Test
    fun `decoded dimensions and compressed byte limits are bounded`() {
        val png = png()
        val huge =
            rewrite(png) { name, data ->
                if (name == "IHDR") data.copyOf().also { ByteBuffer.wrap(it).putInt(Int.MAX_VALUE) } else data
            }
        assertFalse(valid(huge))
        assertFalse(valid(png, png.size.toLong() - 1))
        assertTrue(valid(png, png.size.toLong()))
    }

    @Test
    fun `valid image accepts split IDAT chunks and rejects trailing data`() {
        val png = png()
        assertTrue(valid(png))
        assertFalse(valid(png + byteArrayOf(0)))
        val input = DataInputStream(ByteArrayInputStream(png))
        val output = ByteArrayOutputStream()
        val writer = DataOutputStream(output)
        writer.write(input.readNBytes(8))
        while (input.available() > 0) {
            val count = input.readInt()
            val type = input.readNBytes(4)
            val data = input.readNBytes(count)
            input.readInt()
            if (type.toString(Charsets.US_ASCII) == "IDAT") {
                writeChunk(writer, type, data.copyOfRange(0, data.size / 2))
                writeChunk(writer, type, data.copyOfRange(data.size / 2, data.size))
            } else {
                writeChunk(writer, type, data)
            }
        }
        assertTrue(valid(output.toByteArray()))
    }

    private fun png(): ByteArray =
        ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()

    private fun valid(
        bytes: ByteArray,
        limit: Long = 100000,
    ): Boolean {
        val path = Files.createTempFile(directory, "png-", ".png")
        Files.write(path, bytes)
        return validPng(path, limit)
    }

    private fun rewrite(
        png: ByteArray,
        change: (String, ByteArray) -> ByteArray,
    ): ByteArray {
        val input = DataInputStream(ByteArrayInputStream(png))
        val output = ByteArrayOutputStream()
        val writer = DataOutputStream(output)
        writer.write(input.readNBytes(8))
        while (input.available() > 0) {
            val count = input.readInt()
            val type = input.readNBytes(4)
            val data = input.readNBytes(count)
            input.readInt()
            writeChunk(writer, type, change(type.toString(Charsets.US_ASCII), data))
        }
        return output.toByteArray()
    }

    private fun writeChunk(
        writer: DataOutputStream,
        type: ByteArray,
        data: ByteArray,
    ) {
        writer.writeInt(data.size)
        writer.write(type)
        writer.write(data)
        val crc =
            CRC32().apply {
                update(type)
                update(data)
            }
        writer.writeInt(crc.value.toInt())
    }
}
