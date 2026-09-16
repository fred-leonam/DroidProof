package io.github.fredleonam.droidproof.host

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Coordinates cooperating DroidProof host processes; it is not emulator ownership. */
fun interface EmulatorExecutionLeaseProvider {
    fun acquire(serial: String): EmulatorExecutionLease
}

fun interface EmulatorExecutionLease : AutoCloseable {
    override fun close()
}

class EmulatorExecutionLeaseUnavailableException : RuntimeException(
    "The selected emulator is already in use by another DroidProof process on this host.",
)

class FileEmulatorExecutionLeaseProvider(
    private val namespace: Path = Path.of(System.getProperty("java.io.tmpdir"), "droidproof", "execution-leases"),
) : EmulatorExecutionLeaseProvider {
    override fun acquire(serial: String): EmulatorExecutionLease {
        require(DEVICE_SERIAL.matches(serial)) { "Invalid device serial." }
        Files.createDirectories(namespace)
        val name = MessageDigest.getInstance("SHA-256").digest(serial.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val channel = FileChannel.open(namespace.resolve("$name.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val lock =
            try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } catch (error: Exception) {
                channel.close()
                throw EmulatorExecutionLeaseUnavailableException()
            }
        if (lock == null) {
            channel.close()
            throw EmulatorExecutionLeaseUnavailableException()
        }
        return FileEmulatorExecutionLease(channel, lock)
    }

    private class FileEmulatorExecutionLease(
        private val channel: FileChannel,
        private val lock: FileLock,
    ) : EmulatorExecutionLease {
        override fun close() {
            try {
                if (lock.isValid) lock.release()
            } finally {
                channel.close()
            }
        }
    }
}

internal val DEVICE_SERIAL = Regex("[A-Za-z0-9][A-Za-z0-9._:\\[\\]-]{0,255}")
