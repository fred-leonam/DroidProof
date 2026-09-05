package io.github.fredleonam.droidproof.evidence

import io.github.fredleonam.droidproof.model.Sha256
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

object Sha256Calculator {
    private const val BUFFER_SIZE = 8 * 1024

    /** Calculates an artifact or scenario-data SHA-256 without loading the whole file into memory. */
    fun calculate(path: Path): Sha256 =
        Files.newByteChannel(path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            Channels.newInputStream(channel).use(::calculate)
        }

    fun calculate(input: InputStream): Sha256 {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return Sha256(digest.digest().joinToString("") { byte -> "%02x".format(byte) })
    }
}
