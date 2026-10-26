package org.stellar.anchor.platform

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

private val friendbotClient =
  OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(40, TimeUnit.SECONDS)
    .build()

fun fundAccountWithFriendbot(
  accountId: String,
  friendbotBaseUrl: String = "https://friendbot.stellar.org",
  maxAttempts: Int = 5,
) {
  val url = "$friendbotBaseUrl/?addr=$accountId"
  var lastError: Exception? = null
  for (attempt in 1..maxAttempts) {
    try {
      friendbotClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
        if (response.isSuccessful || response.code == 400) {
          return
        }
        lastError = IOException("friendbot returned ${response.code} for $accountId")
      }
    } catch (e: IOException) {
      lastError = e
    }
    if (attempt < maxAttempts) {
      Thread.sleep(1000L * attempt)
    }
  }
  throw IOException(
    "failed to fund $accountId via friendbot after $maxAttempts attempts",
    lastError
  )
}
