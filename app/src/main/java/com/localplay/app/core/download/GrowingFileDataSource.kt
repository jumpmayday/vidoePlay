package com.localplay.app.core.download

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Reads a file that may still be growing (download in progress).
 * On EOF it waits until more bytes appear, or [isFinished] becomes true.
 */
class GrowingFileDataSource(
    private val file: File,
    private val isFinished: () -> Boolean,
    /** Expected final size in bytes, or <= 0 when unknown. Enables seeking during 边下边播. */
    private val expectedTotal: () -> Long = { -1L }
) : BaseDataSource(/* isNetwork= */ false) {

    private var randomAccessFile: RandomAccessFile? = null
    private var bytesRemaining: Long = 0L
    private var opened = false
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        waitUntilReadable(dataSpec.position)
        val raf = RandomAccessFile(file, "r")
        randomAccessFile = raf
        raf.seek(dataSpec.position)
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            else -> {
                // Report full remaining length so the extractor builds a seek map and the
                // player exposes a seekable timeline while the file is still downloading.
                val total = expectedTotal()
                if (total > 0L && total > dataSpec.position) {
                    total - dataSpec.position
                } else {
                    C.LENGTH_UNSET.toLong()
                }
            }
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(bytesRemaining, length.toLong()).toInt()
        }

        while (true) {
            val raf = randomAccessFile ?: return C.RESULT_END_OF_INPUT
            val read = raf.read(buffer, offset, toRead)
            if (read > 0) {
                if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                    bytesRemaining -= read
                }
                bytesTransferred(read)
                return read
            }
            // EOF of current file snapshot
            if (isFinished()) {
                return C.RESULT_END_OF_INPUT
            }
            // Wait for download to append more bytes
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return C.RESULT_END_OF_INPUT
            }
            // RandomAccessFile length is live; just retry read at current pointer
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            randomAccessFile?.close()
        } finally {
            randomAccessFile = null
            if (opened) {
                transferEnded()
                opened = false
            }
        }
    }

    private fun waitUntilReadable(position: Long) {
        val deadline = System.currentTimeMillis() + OPEN_TIMEOUT_MS
        while (true) {
            val len = if (file.exists()) file.length() else 0L
            if (len > position) return
            if (isFinished()) {
                if (len > position) return
                throw java.io.EOFException("下载文件为空或尚未写入可读数据")
            }
            if (System.currentTimeMillis() > deadline) {
                throw java.io.IOException("等待下载数据超时")
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException()
            }
        }
    }

    class Factory(
        private val file: File,
        private val finishedFlag: AtomicBoolean,
        private val expectedTotal: AtomicLong = AtomicLong(-1L)
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return GrowingFileDataSource(
                file,
                { finishedFlag.get() },
                { expectedTotal.get() }
            )
        }
    }

    companion object {
        private const val POLL_MS = 250L
        private const val OPEN_TIMEOUT_MS = 120_000L
    }
}
