package com.grid.app.feature.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

sealed class DriveError(message: String) : Exception(message) {
    class Unauthorized : DriveError("Google Drive access expired")
    class Http(val code: Int) : DriveError("Google Drive error $code")
    class Network(cause: IOException) : DriveError(cause.message ?: "Network error")
}

data class DriveFile(val id: String, val name: String, val size: Long, val createdTime: String?, val appProperties: Map<String, String>)

/**
 * Minimal Drive v3 REST client for the hidden `appDataFolder` (the same space WhatsApp uses):
 * files there are invisible in the user's Drive UI and only this app can read them.
 */
class DriveClient(
    private val http: OkHttpClient,
    private val base: HttpUrl = "https://www.googleapis.com/".toHttpUrl(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class FileDto(
        val id: String,
        val name: String = "",
        val size: String? = null,
        val createdTime: String? = null,
        val appProperties: Map<String, String> = emptyMap(),
    )
    @Serializable private data class FileListDto(val files: List<FileDto> = emptyList())
    @Serializable private data class UserDto(val emailAddress: String? = null)
    @Serializable private data class AboutDto(val user: UserDto? = null)

    suspend fun list(token: String): List<DriveFile> {
        val url = base.newBuilder().addPathSegments("drive/v3/files")
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("fields", "files(id,name,size,createdTime,appProperties)")
            .addQueryParameter("orderBy", "createdTime desc")
            .addQueryParameter("pageSize", "100")
            .build()
        return call(token, Request.Builder().url(url).get()) { body ->
            json.decodeFromString(FileListDto.serializer(), body).files.map { it.toFile() }
        }
    }

    suspend fun upload(token: String, name: String, bytes: ByteArray, appProperties: Map<String, String>): DriveFile {
        val metadata = buildJsonObject {
            put("name", name)
            putJsonArray("parents") { add("appDataFolder") }
            putJsonObject("appProperties") { appProperties.forEach { (k, v) -> put(k, v) } }
        }.toString()
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(bytes.toRequestBody("application/zip".toMediaType()))
            .build()
        val url = base.newBuilder().addPathSegments("upload/drive/v3/files")
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", "id,name,size,createdTime,appProperties")
            .build()
        return call(token, Request.Builder().url(url).post(body)) { json.decodeFromString(FileDto.serializer(), it).toFile() }
    }

    suspend fun download(token: String, id: String): ByteArray {
        val url = base.newBuilder().addPathSegments("drive/v3/files").addPathSegment(id).addQueryParameter("alt", "media").build()
        return withContext(Dispatchers.IO) { execute(token, Request.Builder().url(url).get()) { it.body.bytes() } }
    }

    suspend fun delete(token: String, id: String) {
        val url = base.newBuilder().addPathSegments("drive/v3/files").addPathSegment(id).build()
        call(token, Request.Builder().url(url).delete()) { }
    }

    suspend fun userEmail(token: String): String? {
        val url = base.newBuilder().addPathSegments("drive/v3/about").addQueryParameter("fields", "user(emailAddress)").build()
        return call(token, Request.Builder().url(url).get()) { json.decodeFromString(AboutDto.serializer(), it).user?.emailAddress }
    }

    private suspend fun <T> call(token: String, builder: Request.Builder, parse: (String) -> T): T =
        withContext(Dispatchers.IO) { execute(token, builder) { parse(it.body.string()) } }

    private fun <T> execute(token: String, builder: Request.Builder, read: (Response) -> T): T {
        val request = builder.header("Authorization", "Bearer $token").build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw DriveError.Network(e)
        }
        return response.use {
            when {
                it.code == 401 -> throw DriveError.Unauthorized()
                !it.isSuccessful -> throw DriveError.Http(it.code)
                else -> read(it)
            }
        }
    }

    private fun FileDto.toFile() = DriveFile(id, name, size?.toLongOrNull() ?: 0L, createdTime, appProperties)
}
