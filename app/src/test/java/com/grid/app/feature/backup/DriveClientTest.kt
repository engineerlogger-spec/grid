package com.grid.app.feature.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

class DriveClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DriveClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = DriveClient(OkHttpClient(), server.url("/"))
    }

    @After fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test fun listsAppFolderFilesNewestFirst() = runTest {
        server.enqueue(json("""{"files":[{"id":"b","name":"grid-backup-2.zip","size":"2048","createdTime":"2026-10-04T10:00:00Z","appProperties":{"schema":"1"}},{"id":"a","name":"grid-backup-1.zip","size":"1024"}]}"""))
        val files = client.list("tok")
        assertThat(files.map { it.id }).containsExactly("b", "a").inOrder()
        assertThat(files.first().size).isEqualTo(2048)
        assertThat(files.first().appProperties).containsEntry("schema", "1")
        val request = server.takeRequest()
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer tok")
        assertThat(request.url.queryParameter("spaces")).isEqualTo("appDataFolder")
        assertThat(request.url.encodedPath).isEqualTo("/drive/v3/files")
    }

    @Test fun uploadsMultipartIntoAppFolder() = runTest {
        server.enqueue(json("""{"id":"new","name":"grid-backup.zip","size":"3"}"""))
        val file = client.upload("tok", "grid-backup.zip", byteArrayOf(1, 2, 3), mapOf("schema" to "1"))
        assertThat(file.id).isEqualTo("new")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/upload/drive/v3/files")
        assertThat(request.url.queryParameter("uploadType")).isEqualTo("multipart")
        assertThat(request.headers["Content-Type"]).startsWith("multipart/related")
        val body = request.body!!.utf8()
        assertThat(body).contains("\"parents\":[\"appDataFolder\"]")
        assertThat(body).contains("\"schema\":\"1\"")
    }

    @Test fun downloadsMedia() = runTest {
        server.enqueue(MockResponse.Builder().body("zipbytes").build())
        assertThat(client.download("tok", "abc").decodeToString()).isEqualTo("zipbytes")
        val request = server.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/drive/v3/files/abc")
        assertThat(request.url.queryParameter("alt")).isEqualTo("media")
    }

    @Test fun deletes() = runTest {
        server.enqueue(MockResponse.Builder().code(204).build())
        client.delete("tok", "abc")
        assertThat(server.takeRequest().method).isEqualTo("DELETE")
    }

    @Test fun readsAccountEmail() = runTest {
        server.enqueue(json("""{"user":{"emailAddress":"me@example.com","displayName":"Me"}}"""))
        assertThat(client.userEmail("tok")).isEqualTo("me@example.com")
    }

    @Test fun unauthorizedIsTyped() = runTest {
        server.enqueue(json("""{"error":{"code":401}}""", code = 401))
        val error = runCatching { client.list("expired") }.exceptionOrNull()
        assertThat(error).isInstanceOf(DriveError.Unauthorized::class.java)
    }

    @Test fun serverErrorIsTyped() = runTest {
        server.enqueue(json("{}", code = 503))
        val error = runCatching { client.list("tok") }.exceptionOrNull()
        assertThat((error as DriveError.Http).code).isEqualTo(503)
    }
}
