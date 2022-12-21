package org.stellar.anchor.platform

import kotlin.test.assertFailsWith
import org.stellar.anchor.api.exception.SepNotAuthorizedException
import org.stellar.anchor.api.sep.sep10.ValidationRequest
import org.stellar.anchor.platform.AnchorPlatformIntegrationTest.Companion.toml

var CLIENT_WALLET_EXTRA_SIGNER_1_SECRET = "SDUDRKCL5AX7RDZ7S6JAPBNKLV6LRZXJSHN5OJDP32TIJB42ODPQODHY"
var CLIENT_WALLET_EXTRA_SIGNER_2_SECRET = "SC52GRNSIODLPNGTXUCZ5NHBII4PYUKUVQCCWWIK6OB6P4AW4M37DXZK"

lateinit var sep10Client: Sep10Client
lateinit var sep10ClientMultiSig: Sep10Client

class Sep10Tests {
  companion object {
    internal fun setup() {
      if (!::sep10Client.isInitialized) {
        sep10Client =
          Sep10Client(
            toml.getString("WEB_AUTH_ENDPOINT"),
            toml.getString("SIGNING_KEY"),
            CLIENT_WALLET_ACCOUNT,
            CLIENT_WALLET_SECRET
          )
      }

      if (!::sep10ClientMultiSig.isInitialized) {
        sep10ClientMultiSig =
          Sep10Client(
            toml.getString("WEB_AUTH_ENDPOINT"),
            toml.getString("SIGNING_KEY"),
            CLIENT_WALLET_ACCOUNT,
            arrayOf(
              CLIENT_WALLET_SECRET,
              CLIENT_WALLET_EXTRA_SIGNER_1_SECRET,
              CLIENT_WALLET_EXTRA_SIGNER_2_SECRET
            )
          )
      }
    }

    fun testMultiSig() {
      sep10ClientMultiSig.auth()
    }

    fun testOk(): String {
      return sep10Client.auth()
    }

    fun testUnsignedChallenge() {
      val challenge = sep10Client.challenge()

      assertFailsWith(
        exceptionClass = SepNotAuthorizedException::class,
        block = { sep10Client.validate(ValidationRequest.of(challenge.transaction)) }
      )
    }
  }
}

fun sep10TestAll() {
  Sep10Tests.setup()
  println("Performing SEP10 tests...")

  Sep10Tests.testOk()
  Sep10Tests.testUnsignedChallenge()
  Sep10Tests.testMultiSig()
}
