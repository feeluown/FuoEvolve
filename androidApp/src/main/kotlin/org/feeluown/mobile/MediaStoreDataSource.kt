package org.feeluown.mobile

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSourceException
import java.io.EOFException
import java.io.FileInputStream
import java.io.IOException

/**
 * Reads MediaStore content through a file descriptor rather than an asset file descriptor.
 *
 * Some Android 10 MediaProvider implementations return an asset descriptor which never
 * becomes readable. A regular parcel file descriptor is supported by those providers and
 * avoids leaving ExoPlayer in STATE_BUFFERING indefinitely.
 */
@UnstableApi
internal class MediaStoreDataSource(
    private val contentResolver: ContentResolver,
) : BaseDataSource(false) {
    private var inputStream: FileInputStream? = null
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var openedUri: Uri? = null
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        openedUri = dataSpec.uri
        try {
            val descriptor = contentResolver.openFileDescriptor(dataSpec.uri, "r")
                ?: throw IOException("Unable to open MediaStore item: ${dataSpec.uri}")
            parcelFileDescriptor = descriptor
            val stream = FileInputStream(descriptor.fileDescriptor)
            inputStream = stream

            val skipped = stream.channel.position(dataSpec.position).position()
            if (skipped != dataSpec.position) throw EOFException()

            val availableLength = descriptor.statSize.takeIf { it >= 0L }
                ?.minus(dataSpec.position)
                ?.coerceAtLeast(0L)
                ?: C.LENGTH_UNSET.toLong()
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                availableLength
            }
        } catch (exception: IOException) {
            closeQuietly()
            throw DataSourceException(
                exception,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            )
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val requested = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        return try {
            inputStream?.read(buffer, offset, requested)?.also { read ->
                if (read > 0 && bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
                if (read > 0) bytesTransferred(read)
            } ?: C.RESULT_END_OF_INPUT
        } catch (exception: IOException) {
            throw DataSourceException(exception, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        openedUri = null
        try {
            closeQuietly()
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private fun closeQuietly() {
        runCatching { inputStream?.close() }
        inputStream = null
        runCatching { parcelFileDescriptor?.close() }
        parcelFileDescriptor = null
    }

    class Factory(private val contentResolver: ContentResolver) : DataSource.Factory {
        override fun createDataSource(): DataSource = MediaStoreDataSource(contentResolver)
    }
}
