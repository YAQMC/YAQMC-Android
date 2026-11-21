package org.yaqmc.android

import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidDiagnosticUploaderTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun sendsOnlyTheZipAndSubmissionIdToTheFeedbackEndpoint() = runBlocking {
        val bundle = folder.newFile("diagnostics.zip")
        ZipOutputStream(bundle.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("diagnostics.txt"))
            zip.write("synthetic test output".toByteArray())
            zip.closeEntry()
        }
        val submissionId = UUID.randomUUID().toString()
        val receipt = "1234567890abcdef1234567890abcdef"
        val progress = mutableListOf<Int>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals(DIAGNOSTIC_UPLOAD_URL, request.url.toString())
            assertEquals("POST", request.method)
            assertEquals(submissionId, request.header("X-Submission-Id"))
            assertNull(request.header("Authorization"))
            assertNull(request.header("Cookie"))
            val body = checkNotNull(request.body)
            assertEquals("application/zip", body.contentType().toString())
            assertEquals(bundle.length(), body.contentLength())
            val received = Buffer()
            body.writeTo(received)
            assertArrayEquals(bundle.readBytes(), received.readByteArray())
            response(request, 201, """{"receipt":"$receipt"}""")
        }.build()
        assertEquals(receipt, AndroidDiagnosticUploader(client).submit(bundle, submissionId) { progress += it })
        assertEquals(100, progress.last())
    }

    @Test fun rejectsAmbiguousReceiptsAndPreservesServerStatus() {
        val bundle = folder.newFile("diagnostics.zip").apply { writeText("test archive") }
        val submissionId = UUID.randomUUID().toString()
        fun client(code: Int, body: String) = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), code, body)
        }.build()

        val rejected = assertThrows(DiagnosticUploadHttpError::class.java) {
            runBlocking { AndroidDiagnosticUploader(client(429, "")).submit(bundle, submissionId) {} }
        }
        assertEquals(429, rejected.status)
        assertThrows(IOException::class.java) {
            runBlocking { AndroidDiagnosticUploader(client(200, """{"receipt":"invalid"}""")).submit(bundle, submissionId) {} }
        }
    }

    private fun response(request: Request, code: Int, body: String): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
        .body(body.toResponseBody()).build()
}
