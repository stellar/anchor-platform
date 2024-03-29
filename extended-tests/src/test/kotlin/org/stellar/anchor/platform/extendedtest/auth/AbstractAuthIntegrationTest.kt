package org.stellar.anchor.platform.extendedtest.auth

import org.stellar.anchor.auth.AuthHelper
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.platform.TestProfileExecutor
import org.stellar.anchor.platform.testutil.toSecretKey

abstract class AbstractAuthIntegrationTest {
  companion object {
    const val ANCHOR_TO_PLATFORM_SECRET = "myAnchorToPlatformSecret1234567890"
    const val PLATFORM_TO_ANCHOR_SECRET = "myPlatformToAnchorSecret1234567890"
    const val PLATFORM_TO_CUSTODY_SECRET = "myPlatformToCustodySecret1234567890"
    const val PLATFORM_SERVER_PORT = 8085
    const val CUSTODY_SERVER_SERVER_PORT = 8086
    const val REFERENCE_SERVER_PORT = 8091
    const val JWT_EXPIRATION_MILLISECONDS = 10000L

    const val GET_TRANSACTIONS_ENDPOINT = "GET,/transactions"
    const val PATCH_TRANSACTIONS_ENDPOINT = "PATCH,/transactions"
    const val GET_TRANSACTIONS_MY_ID_ENDPOINT = "GET,/transactions/my_id"
    const val GET_EXCHANGE_QUOTES_ENDPOINT = "GET,/exchange/quotes"
    const val GET_EXCHANGE_QUOTES_ID_ENDPOINT = "GET,/exchange/quotes/id"
    const val POST_CUSTODY_TRANSACTION_ENDPOINT = "POST,/transactions"

    private val jwtService =
      JwtService(
        null,
        null,
        null,
        PLATFORM_TO_ANCHOR_SECRET.toSecretKey(),
        ANCHOR_TO_PLATFORM_SECRET.toSecretKey(),
        PLATFORM_TO_CUSTODY_SECRET.toSecretKey()
      )
    private val jwtWrongKeyService =
      JwtService(
        null,
        null,
        null,
        (PLATFORM_TO_ANCHOR_SECRET + "bad").toSecretKey(),
        (ANCHOR_TO_PLATFORM_SECRET + "bad").toSecretKey(),
        (PLATFORM_TO_CUSTODY_SECRET + "bad").toSecretKey()
      )

    internal val jwtAuthHelper = AuthHelper.forJwtToken(jwtService, 10000)
    internal val jwtWrongKeyAuthHelper = AuthHelper.forJwtToken(jwtWrongKeyService, 10000)
    internal val jwtExpiredAuthHelper = AuthHelper.forJwtToken(jwtService, 0)
    internal lateinit var testProfileRunner: TestProfileExecutor
  }
}
