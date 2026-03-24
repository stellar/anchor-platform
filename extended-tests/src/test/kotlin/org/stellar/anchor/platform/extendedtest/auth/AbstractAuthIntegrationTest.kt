package org.stellar.anchor.platform.extendedtest.auth

import org.stellar.anchor.auth.ApiAuthJwt.CustodyAuthJwt
import org.stellar.anchor.auth.ApiAuthJwt.PlatformAuthJwt
import org.stellar.anchor.auth.AuthHelper
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.platform.TestProfileExecutor

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
        null,
        null,
        PLATFORM_TO_ANCHOR_SECRET,
        ANCHOR_TO_PLATFORM_SECRET,
        PLATFORM_TO_CUSTODY_SECRET,
      )
    private val jwtWrongKeyService =
      JwtService(
        null,
        null,
        null,
        null,
        null,
        (PLATFORM_TO_ANCHOR_SECRET + "bad"),
        (ANCHOR_TO_PLATFORM_SECRET + "bad"),
        (PLATFORM_TO_CUSTODY_SECRET + "bad"),
      )

    internal val platformJwtAuthHelper =
      AuthHelper.forJwtToken(jwtService, 10000, PlatformAuthJwt::class.java)
    internal val platformJwtWrongKeyAuthHelper =
      AuthHelper.forJwtToken(jwtWrongKeyService, 10000, PlatformAuthJwt::class.java)
    internal val platformJwtExpiredAuthHelper =
      AuthHelper.forJwtToken(jwtService, 0, PlatformAuthJwt::class.java)

    internal val custodyJwtAuthHelper =
      AuthHelper.forJwtToken(jwtService, 10000, CustodyAuthJwt::class.java)
    internal val custodyJwtWrongKeyAuthHelper =
      AuthHelper.forJwtToken(jwtWrongKeyService, 10000, CustodyAuthJwt::class.java)
    internal val custodyJwtExpiredAuthHelper =
      AuthHelper.forJwtToken(jwtService, 0, CustodyAuthJwt::class.java)
    internal lateinit var testProfileRunner: TestProfileExecutor
  }
}
