package org.yaqmc.android

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject

internal const val DIAGNOSTIC_UPLOAD_URL = "https://ltzu.top/api/yaqmc-feedback/bundles"
internal const val DIAGNOSTIC_UPLOAD_MAX_BYTES = 9 * 1024 * 1024L
internal const val DIAGNOSTIC_DESCRIPTION_MAX_BYTES = 1000

internal class DiagnosticUploadHttpError(val status: Int) : IOException()

internal class AndroidDiagnosticUploader(private val testClient: OkHttpClient? = null) {
    suspend fun submit(file: File, submissionId: String, onProgress: (Int) -> Unit): String =
        suspendCancellableCoroutine { continuation ->
            val size = file.length()
            if (!file.isFile || size <= 0) throw IOException("Diagnostic file is missing")
            if (size > DIAGNOSTIC_UPLOAD_MAX_BYTES) throw DiagnosticUploadHttpError(413)
            val body = object : RequestBody() {
                override fun contentType() = "application/zip".toMediaType()
                override fun contentLength() = size

                override fun writeTo(sink: BufferedSink) {
                    file.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var written = 0L
                        var progress = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            sink.write(buffer, 0, count)
                            written += count
                            val next = (100L * written / size).toInt().coerceAtMost(100)
                            if (next != progress) {
                                progress = next
                                onProgress(progress)
                            }
                        }
                    }
                }
            }
            val request = Request.Builder().url(DIAGNOSTIC_UPLOAD_URL)
                .header("X-Submission-Id", submissionId)
                .post(body)
                .build()
            val call = (testClient ?: anonymousClient).newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(runCatching {
                        response.use {
                            if (it.code != 200 && it.code != 201) throw DiagnosticUploadHttpError(it.code)
                            val source = it.body?.source() ?: throw IOException("Missing feedback receipt")
                            if (source.request(1025L)) throw IOException("Feedback receipt exceeds the response limit")
                            val receipt = JSONObject(source.readUtf8()).optString("receipt")
                            if (!receipt.matches(receiptPattern)) throw IOException("Invalid feedback receipt")
                            receipt
                        }
                    })
                }
            })
        }

    private companion object {
        val receiptPattern = Regex("[0-9a-f]{32}")
        val anonymousClient by lazy {
            OkHttpClient.Builder()
                .cookieJar(CookieJar.NO_COOKIES)
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .callTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
