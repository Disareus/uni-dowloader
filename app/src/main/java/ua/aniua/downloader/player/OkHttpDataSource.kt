package ua.aniua.downloader.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Media3 DataSource, що працює поверх OkHttpClient додатку (DoH, HTTP/2, TLS та редіректи).
 */
@UnstableApi
class OkHttpDataSource(
    private val callFactory: Call.Factory,
    initialProperties: Map<String, String> = emptyMap()
) : BaseDataSource(/* isNetwork = */ true), HttpDataSource {

    class Factory(private val callFactory: Call.Factory) : HttpDataSource.Factory {
        private val defaultRequestProperties = ConcurrentHashMap<String, String>()

        override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): HttpDataSource.Factory {
            this.defaultRequestProperties.clear()
            this.defaultRequestProperties.putAll(defaultRequestProperties)
            return this
        }

        override fun createDataSource(): HttpDataSource {
            return OkHttpDataSource(callFactory, defaultRequestProperties)
        }
    }

    private val requestProperties = HashMap<String, String>(initialProperties)
    private var dataSpec: DataSpec? = null
    private var response: Response? = null
    private var responseStream: InputStream? = null
    private var opened = false
    private var bytesToRead = 0L
    private var bytesRead = 0L

    override fun setRequestProperty(name: String, value: String) {
        requestProperties[name] = value
    }

    override fun clearRequestProperty(name: String) {
        requestProperties.remove(name)
    }

    override fun clearAllRequestProperties() {
        requestProperties.clear()
    }

    override fun getUri(): Uri? {
        return response?.request?.url?.toString()?.let(Uri::parse) ?: dataSpec?.uri
    }

    override fun getResponseCode(): Int = response?.code ?: -1

    override fun getResponseHeaders(): Map<String, List<String>> =
        response?.headers?.toMultimap() ?: emptyMap()

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        this.bytesRead = 0L
        this.bytesToRead = 0L
        transferInitializing(dataSpec)

        val request = buildOkHttpRequest(dataSpec)
        val resp = try {
            callFactory.newCall(request).execute()
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        }
        response = resp

        if (!resp.isSuccessful) {
            val code = resp.code
            val message = resp.message
            val headers = resp.headers.toMultimap()
            closeQuietly()
            throw HttpDataSource.InvalidResponseCodeException(
                code,
                message,
                null,
                headers,
                dataSpec,
                ByteArray(0)
            )
        }

        val body = resp.body ?: run {
            closeQuietly()
            throw HttpDataSource.HttpDataSourceException(
                "Empty HTTP response body",
                dataSpec,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        }

        val contentLength = body.contentLength()
        bytesToRead = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else if (contentLength != -1L) {
            contentLength
        } else {
            C.LENGTH_UNSET.toLong()
        }

        responseStream = body.byteStream()
        opened = true
        transferStarted(dataSpec)
        return bytesToRead
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val currentSpec = dataSpec
        val stream = responseStream ?: return C.RESULT_END_OF_INPUT

        val readLength = if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val remaining = bytesToRead - bytesRead
            if (remaining <= 0L) return C.RESULT_END_OF_INPUT
            minOf(length.toLong(), remaining).toInt()
        } else {
            length
        }

        val read = try {
            stream.read(buffer, offset, readLength)
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                currentSpec ?: DataSpec(Uri.EMPTY),
                HttpDataSource.HttpDataSourceException.TYPE_READ
            )
        }
        if (read == -1) return C.RESULT_END_OF_INPUT
        bytesRead += read
        bytesTransferred(read)
        return read
    }

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        closeQuietly()
        dataSpec = null
    }

    private fun buildOkHttpRequest(dataSpec: DataSpec): Request {
        val builder = Request.Builder().url(dataSpec.uri.toString())

        val merged = LinkedHashMap<String, String>(requestProperties.size + dataSpec.httpRequestHeaders.size)
        for ((k, v) in requestProperties) {
            merged[k] = v
        }
        for ((k, v) in dataSpec.httpRequestHeaders) {
            merged[k] = v
        }
        for ((k, v) in merged) {
            builder.header(k, v)
        }

        if (dataSpec.position != 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
            val rangeValue = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                "bytes=${dataSpec.position}-${dataSpec.position + dataSpec.length - 1}"
            } else {
                "bytes=${dataSpec.position}-"
            }
            builder.header("Range", rangeValue)
        }

        val body = dataSpec.httpBody
        val requestBody = when {
            body != null -> body.toRequestBody("application/octet-stream".toMediaTypeOrNull())
            dataSpec.httpMethod == DataSpec.HTTP_METHOD_POST -> ByteArray(0).toRequestBody(null)
            else -> null
        }

        when (dataSpec.httpMethod) {
            DataSpec.HTTP_METHOD_POST -> builder.post(requestBody!!)
            DataSpec.HTTP_METHOD_HEAD -> builder.head()
            else -> builder.get()
        }
        return builder.build()
    }

    private fun closeQuietly() {
        try {
            responseStream?.close()
        } catch (_: Exception) {}
        responseStream = null
        try {
            response?.close()
        } catch (_: Exception) {}
        response = null
    }
}
