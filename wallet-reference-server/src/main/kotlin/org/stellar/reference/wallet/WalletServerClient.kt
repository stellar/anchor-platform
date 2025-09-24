package org.stellar.reference.wallet

import com.google.gson.reflect.TypeToken
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.stellar.anchor.api.sep.sep12.Sep12GetCustomerResponse
import org.stellar.anchor.util.GsonUtils

class WalletServerClient(private val endpoint: Url = Url("http://localhost:8092")) {
  private val gson = GsonUtils.getInstance()
  internal val client = HttpClient()

  suspend fun <T> getTransactionCallbacks(
    sep: String,
    txnId: String? = null,
    responseType: Class<T>
  ): List<T> =
    client
      .get {
        url {
          setupUrl("/callbacks/$sep")
          txnId?.let { parameter("txnId", it) }
        }
      }
      .body<String>()
      .let { parseResponse(it, responseType) }

  suspend fun <T> pollTransactionCallbacks(
    sep: String,
    txnId: String?,
    expected: Int,
    responseType: Class<T>
  ): List<T> = poll(expected) { getTransactionCallbacks(sep, txnId, responseType) }

  suspend fun getCustomerCallbacks(id: String?): List<Sep12GetCustomerResponse> =
    client
      .get {
        url {
          setupUrl("/callbacks/sep12")
          id?.let { parameter("id", it) }
        }
      }
      .body<String>()
      .let { parseResponse(it, Sep12GetCustomerResponse::class.java) }

  suspend fun pollCustomerCallbacks(id: String?, expected: Int): List<Sep12GetCustomerResponse> =
    poll(expected) { getCustomerCallbacks(id) }

  suspend fun clearCallbacks() {
    client.delete { url { setupUrl("/events") } }
  }

  private fun URLBuilder.setupUrl(path: String) {
    protocol = endpoint.protocol
    host = endpoint.host
    port = endpoint.port
    encodedPath = path
  }

  private fun <T> parseResponse(response: String, responseType: Class<T>): List<T> =
    gson.fromJson(response, TypeToken.getParameterized(List::class.java, responseType).type)

  private suspend fun <T> poll(expected: Int, fetcher: suspend () -> List<T>): List<T> {
    repeat(5) {
      val callbacks = fetcher()
      if (callbacks.size >= expected) return callbacks
      delay(5.seconds)
    }
    return fetcher()
  }
}
