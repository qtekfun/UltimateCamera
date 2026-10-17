package com.qtekfun.ultimatecamera.core.codec.mp4

import android.system.ErrnoException
import android.system.Os
import java.io.EOFException
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Random access to a finished take's file for [Mp4PostProcessor]: positioned reads and writes, so nothing larger than a
 * copy buffer is ever held in memory (owner rule: disk, not RAM).
 */
interface SeekableFile {
    @Throws(IOException::class)
    fun size(): Long

    /** Reads exactly [length] bytes at [position] or throws ([EOFException] past the end). */
    @Throws(IOException::class)
    fun read(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)

    @Throws(IOException::class)
    fun write(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)

    @Throws(IOException::class)
    fun truncate(size: Long)

    /** Flushes written data to the device (fsync). */
    @Throws(IOException::class)
    fun sync()
}

/** A [SeekableFile] over a JVM [FileChannel] (tests, files the app owns). */
class ChannelSeekableFile(private val channel: FileChannel) : SeekableFile {
    override fun size(): Long = channel.size()

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        val target = ByteBuffer.wrap(buffer, offset, length)
        var at = position
        while (target.hasRemaining()) {
            val n = channel.read(target, at)
            if (n < 0) throw EOFException("read past the end at $at")
            at += n
        }
    }

    override fun write(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        val source = ByteBuffer.wrap(buffer, offset, length)
        var at = position
        while (source.hasRemaining()) at += channel.write(source, at)
    }

    override fun truncate(size: Long) {
        channel.truncate(size)
    }

    override fun sync() = channel.force(true)
}

/**
 * A [SeekableFile] over a SAF or MediaStore descriptor opened read-write (`pread`/`pwrite`, so the descriptor's own
 * offset is never moved). The caller keeps ownership of [fd].
 */
class DescriptorSeekableFile(private val fd: FileDescriptor) : SeekableFile {
    override fun size(): Long = errno { Os.fstat(fd).st_size }

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            val n = errno { Os.pread(fd, buffer, offset + done, length - done, position + done) }
            if (n <= 0) throw EOFException("read past the end at ${position + done}")
            done += n
        }
    }

    override fun write(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            done += errno { Os.pwrite(fd, buffer, offset + done, length - done, position + done) }
        }
    }

    override fun truncate(size: Long) = errno { Os.ftruncate(fd, size) }

    override fun sync() = errno { Os.fsync(fd) }

    private inline fun <T> errno(block: () -> T): T = try {
        block()
    } catch (e: ErrnoException) {
        throw IOException(e.message, e)
    }
}
