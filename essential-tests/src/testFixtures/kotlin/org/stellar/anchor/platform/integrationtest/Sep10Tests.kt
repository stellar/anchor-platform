package org.stellar.anchor.platform.integrationtest

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import io.ktor.client.plugins.*
import io.ktor.http.*
import java.io.IOException
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.api.exception.SepException
import org.stellar.anchor.api.exception.SepNotAuthorizedException
import org.stellar.anchor.api.sep.sep10.ValidationRequest
import org.stellar.anchor.client.Sep10Client
import org.stellar.anchor.platform.*
import org.stellar.anchor.platform.TestSecrets.CLIENT_WALLET_EXTRA_SIGNER_1_SECRET
import org.stellar.anchor.platform.TestSecrets.CLIENT_WALLET_EXTRA_SIGNER_2_SECRET
import org.stellar.anchor.platform.TestSecrets.CLIENT_WALLET_SECRET
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Transaction
import org.stellar.walletsdk.auth.DefaultAuthHeaderSigner
import org.stellar.walletsdk.auth.DomainAuthHeaderSigner
import org.stellar.walletsdk.auth.WalletSigner
import org.stellar.walletsdk.horizon.AccountKeyPair
import org.stellar.walletsdk.horizon.SigningKeyPair
import org.stellar.walletsdk.horizon.sign

class Sep10Tests : AbstractIntegrationTests(TestConfig()) {
  lateinit var sep10Client: Sep10Client
  lateinit var sep10ClientMultiSig: Sep10Client
  lateinit var webAuthDomain: String

  private val walletDomain = config.env["wallet.server.url"]?.replace("http://", "")!!
  private val walletUrl = config.env["wallet.server.url"]!!
  private val domainSinger = WalletSigner.DomainSigner("$walletUrl/signChallenge")

  init {
    if (!::sep10Client.isInitialized) {
      sep10Client =
        Sep10Client(
          toml.getString("WEB_AUTH_ENDPOINT"),
          toml.getString("SIGNING_KEY"),
          KeyPair.fromSecretSeed(CLIENT_WALLET_SECRET).accountId,
          CLIENT_WALLET_SECRET,
        )
    }
    if (!::sep10ClientMultiSig.isInitialized) {
      sep10ClientMultiSig =
        Sep10Client(
          toml.getString("WEB_AUTH_ENDPOINT"),
          toml.getString("SIGNING_KEY"),
          KeyPair.fromSecretSeed(CLIENT_WALLET_SECRET).accountId,
          arrayOf(
            CLIENT_WALLET_SECRET,
            CLIENT_WALLET_EXTRA_SIGNER_1_SECRET,
            CLIENT_WALLET_EXTRA_SIGNER_2_SECRET,
          ),
        )
    }
    webAuthDomain = toml.getString("WEB_AUTH_ENDPOINT").split("/")[2]
  }

  @Test
  fun testChallengeSerialization() {
    val response = sep10Client.challengeJson()
    val transactionStartIndex = response.indexOf("\"transaction\"") + 15
    val transactionEndIndex = response.indexOf(",", transactionStartIndex) - 1
    val transaction = response.substring(transactionStartIndex, transactionEndIndex)
    Base64.getDecoder().decode(transaction)
  }

  @Test
  fun testAuth() {
    sep10Client.auth(webAuthDomain)
  }

  @Test
  fun testAuthWithWildcardDomain() {
    sep10Client.auth("test.stellar.org")
  }

  @Test
  fun testAuthWithWildcardDomainFail() {
    assertFailsWith(
      exceptionClass = SepException::class,
      block = { sep10Client.auth("bad.domain.org") },
    )
  }

  @Test
  fun testMultiSig() {
    sep10ClientMultiSig.auth(webAuthDomain)
  }

  @Test
  fun testUnsignedChallenge() {
    val challenge = sep10Client.challenge()

    assertFailsWith(
      exceptionClass = SepNotAuthorizedException::class,
      block = { sep10Client.validate(ValidationRequest.of(challenge.transaction)) },
    )
  }

  @Test
  fun testCustodial() = runBlocking {
    val rnd = wallet.stellar().account().createKeyPair()
    val memo = 123UL
    val token = anchor.sep10().authenticate(rnd, memoId = memo)
    assertEquals(rnd.address, token.account)
    assertEquals(memo, token.memo)
  }

  @Test
  fun testCustodialNoMemo() = runBlocking {
    val rnd = wallet.stellar().account().createKeyPair()
    val token = anchor.sep10().authenticate(rnd)
    assertEquals(rnd.address, token.account)
  }

  @Test
  fun testNonCustodial() = runBlocking {
    val accountKp = SigningKeyPair(KeyPair.random())
    val token = anchor.sep10().authenticate(accountKp, domainSinger, clientDomain = walletDomain)

    assertEquals(accountKp.address, token.account)
    assertEquals(walletDomain, token.clientDomain)
  }

  @Test
  fun testNonCustodialWrongKey() = runBlocking {
    val rnd = wallet.stellar().account().createKeyPair()
    val dummyKey = wallet.stellar().account().createKeyPair()
    // Sign with incorrect noncustodial key
    val dummySigner =
      object : WalletSigner.DefaultSigner() {
        override suspend fun signWithDomainAccount(
          transactionXDR: String,
          networkPassPhrase: String,
          account: AccountKeyPair
        ): Transaction {
          return wallet.stellar().decodeTransaction(transactionXDR).sign(dummyKey) as Transaction
        }
      }
    val ex =
      assertThrows<ClientRequestException> {
        anchor.sep10().authenticate(rnd, dummySigner, clientDomain = walletDomain)
      }
  }

  @Test
  fun testCustodialWithAuthHeader() = runBlocking {
    val signer = DefaultAuthHeaderSigner()
    val accountKp =
      SigningKeyPair.fromSecret("SBPPLU2KO3PDBLSDFIWARQSW5SAOIHTJDUQIWN3BQS7KPNMVUDSU37QO")
    val memo = 1234567UL

    anchor.sep10().authenticate(accountKp, memoId = memo, authHeaderSigner = signer)
    return@runBlocking
  }

  @Test
  fun testNonCustodialWithAuthHeader() = runBlocking {
    val accountKp = SigningKeyPair(KeyPair.random())
    val domainAuthHeaderSigner = DomainAuthHeaderSigner("$walletUrl/signHeader")
    anchor
      .sep10()
      .authenticate(
        accountKp,
        domainSinger,
        clientDomain = walletDomain,
        authHeaderSigner = domainAuthHeaderSigner
      )
    return@runBlocking
  }

  class RawStringTypeAdapter : TypeAdapter<String>() {
    @Throws(IOException::class)
    override fun write(out: JsonWriter, value: String?) {
      if (value == null) {
        out.nullValue()
      } else {
        out.jsonValue("\"$value\"")
      }
    }

    @Throws(IOException::class)
    override fun read(reader: JsonReader): String {
      return reader.nextString()
    }
  }
}
