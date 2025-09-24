package org.stellar.reference.wallet

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.moandjiezana.toml.Toml
import io.jsonwebtoken.Jwts
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import java.time.Instant
import kotlinx.datetime.Clock
import org.stellar.anchor.util.GsonUtils
import org.stellar.reference.wallet.CallbackService.Companion.verifySignature
import org.stellar.sdk.KeyPair
import org.stellar.walletsdk.StellarConfiguration
import org.stellar.walletsdk.Wallet
import org.stellar.walletsdk.auth.DefaultAuthHeaderSigner
import org.stellar.walletsdk.auth.DomainAuthHeaderSigner
import org.stellar.walletsdk.auth.WalletSigner
import org.stellar.walletsdk.horizon.AccountKeyPair
import org.stellar.walletsdk.horizon.SigningKeyPair
import org.stellar.walletsdk.horizon.sign
import org.stellar.walletsdk.util.toJava

class Routes(private val config: Config, private val callbackEventService: CallbackService) {
  private var signer: KeyPair? = null
  private val gson: Gson = GsonUtils.getInstance()
  private val wallet = Wallet(StellarConfiguration.Testnet)
  private val key = SigningKeyPair.fromSecret(config.secret.key)
  private val domainSigner = createDomainSigner()

  fun Route.setupCallbackRoutes() {
    callback()
  }

  fun Route.setupNoncustodialRoutes() {
    noncustodial()
  }

  private fun Route.callback() {
    route("/callbacks/{sep}") {
      post { handleCallbackPost() }
      get { handleCallbackGet() }
    }
  }

  private suspend fun PipelineContext<Unit, ApplicationCall>.handleCallbackPost() {
    val header = call.request.headers["Signature"]
    val body = call.receive<String>()

    if (!verifyCallbackSignature(header, body)) {
      call.response.status(HttpStatusCode.Forbidden)
      return
    }

    val event: JsonObject = gson.fromJson(body, JsonObject::class.java)
    callbackEventService.processCallback(event, call.parameters["sep"]!!)
    call.respond("POST /callback received")
  }

  private suspend fun PipelineContext<Unit, ApplicationCall>.handleCallbackGet() {
    val response =
      when (val sep = call.parameters["sep"]) {
        "sep12" -> {
          val id = call.parameters["id"]
          if (id != null) {
            gson.toJson(callbackEventService.getCustomerCallbacks(id))
          } else {
            "Error: Missing 'id' parameter for SEP-12 callback"
          }
        }
        else -> {
          val txnId = call.parameters["txnId"]
          if (sep != null && txnId != null) {
            gson.toJson(callbackEventService.getTransactionCallbacks(sep, txnId))
          } else {
            "Error: Missing 'sep' or 'txnId' parameter"
          }
        }
      }
    call.respond(response)
  }

  private suspend fun verifyCallbackSignature(header: String?, body: String): Boolean {
    if (signer == null) {
      signer = KeyPair.fromAccountId(fetchSigningKey(config))
    }
    return verifySignature(header, body, config.wallet.hostname, signer)
  }

  private suspend fun fetchSigningKey(config: Config): String {
    val endpoint = Url(config.anchor.endpoint)
    val client = HttpClient()

    val response =
      client.get {
        url {
          this.protocol = endpoint.protocol
          host = endpoint.host
          port = endpoint.port
          encodedPath = "/.well-known/stellar.toml"
        }
      }
    return Toml().read(response.body<String>()).getString("SIGNING_KEY")
  }

  private fun Route.noncustodial() {
    route("/signChallenge") { post { handleSignChallenge() } }
    route("/signHeader") { post { handleSignHeader() } }
  }

  private suspend fun PipelineContext<Unit, ApplicationCall>.handleSignChallenge() {
    val body = call.receive<WalletSigner.DomainSigner.SigningData>()
    val transaction = wallet.stellar().decodeTransaction(body.transaction)
    val signed = transaction.sign(key).toEnvelopeXdrBase64()
    call.respond(WalletSigner.DomainSigner.SigningData(signed, body.networkPassphrase))
  }

  private suspend fun PipelineContext<Unit, ApplicationCall>.handleSignHeader() {
    val body = call.receive<DomainAuthHeaderSigner.JWTSignData>()
    val token = domainSigner.createToken(body.claims, body.clientDomain, null)
    call.respond(DomainAuthHeaderSigner.SignedJWT(token))
  }

  private fun createDomainSigner() =
    object : DefaultAuthHeaderSigner() {
      override suspend fun createToken(
        claims: Map<String, String>,
        clientDomain: String?,
        issuer: AccountKeyPair?
      ): String {
        val timeExp = Instant.ofEpochSecond(Clock.System.now().plus(expiration).epochSeconds)
        val builder = createBuilder(timeExp, claims)
        builder.signWith(key.toJava().private, Jwts.SIG.EdDSA)
        return builder.compact()
      }
    }
}

fun Route.callback(config: Config, callbackEventService: CallbackService) {
  Routes(config, callbackEventService).apply { setupCallbackRoutes() }
}

fun Route.noncustodial(config: Config) {
  Routes(config, CallbackService()).apply { setupNoncustodialRoutes() }
}
