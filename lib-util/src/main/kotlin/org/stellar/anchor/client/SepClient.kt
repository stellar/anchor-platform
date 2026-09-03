package org.stellar.anchor.client

import com.google.gson.Gson
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.apache.hc.core5.http.HttpStatus
import org.stellar.anchor.api.exception.SepException
import org.stellar.anchor.api.exception.SepNotAuthorizedException
import org.stellar.anchor.api.exception.SepNotFoundException
import org.stellar.anchor.api.sep.SepExceptionResponse
import org.stellar.anchor.util.GsonUtils

open class SepClient {
  companion object {
    val gson: Gson = GsonUtils.getInstance()
    val client =
      OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.MINUTES)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()
  }

  fun httpGet(url: String, jwt: String? = null): String? {
    var builder = Request.Builder().url(url).header("Content-Type", "application/json").get()
    if (jwt != null) {
      builder = builder.header("Authorization", "Bearer $jwt")
    }
    val request = builder.build()

    val response = client.newCall(request).execute()
    return handleResponse(response)
  }

  fun httpPost(url: String, requestBody: Map<String, Any>, jwt: String? = null): String? {
    val headers = if (jwt != null) mapOf("Authorization" to "Bearer $jwt") else mapOf()
    return httpPost(url, requestBody, headers)
  }

  fun httpPost(url: String, requestBodyStr: String, headers: Map<String, String>): String? {
    val requestBody = requestBodyStr.toRequestBody(TYPE_JSON)
    val request =
      Request.Builder()
        .url(url)
        .header("Content-Type", "application/json")
        .apply { headers.forEach { (key, value) -> header(key, value) } }
        .post(requestBody)
        .build()

    val response = client.newCall(request).execute()
    return handleResponse(response)
  }

  fun httpPost(url: String, requestBody: Map<String, Any>, headers: Map<String, String>): String? {
    return httpPost(url, gson.toJson(requestBody), headers)
  }

  fun httpPatch(url: String, requestBody: Map<String, Any>, jwt: String? = null): String? {
    val headers = if (jwt != null) mapOf("Authorization" to "Bearer $jwt") else mapOf()
    return httpPatch(url, requestBody, headers)
  }

  fun httpPatch(url: String, requestBodyStr: String, headers: Map<String, String>): String? {
    val requestBody = requestBodyStr.toRequestBody(TYPE_JSON)
    val request =
      Request.Builder()
        .url(url)
        .header("Content-Type", "application/json")
        .apply { headers.forEach { (key, value) -> header(key, value) } }
        .patch(requestBody)
        .build()

    val response = client.newCall(request).execute()
    return handleResponse(response)
  }

  fun httpPatch(url: String, requestBody: Map<String, Any>, headers: Map<String, String>): String? {
    return httpPatch(url, gson.toJson(requestBody), headers)
  }

  fun handleResponse(response: Response): String? {
    val responseBody = response.body?.string()

    when (response.code) {
      HttpStatus.SC_OK,
      HttpStatus.SC_CREATED,
      HttpStatus.SC_ACCEPTED -> return responseBody
      HttpStatus.SC_FORBIDDEN -> throw SepNotAuthorizedException("Forbidden")
      HttpStatus.SC_NOT_FOUND -> {
        val sepException = gson.fromJson(responseBody, SepExceptionResponse::class.java)
        throw SepNotFoundException(sepException.error)
      }
      else -> throw SepException(responseBody)
    }
  }
}
