package org.stellar.anchor.platform.integrationtest

import java.net.URI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.api.sep.sep45.ChallengeRequest
import org.stellar.anchor.api.sep.sep45.ValidationRequest
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.auth.Sep45Jwt
import org.stellar.anchor.client.Sep45Client
import org.stellar.anchor.platform.*
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.Log
import org.stellar.anchor.xdr.SorobanAuthorizationEntryList
import org.stellar.sdk.KeyPair
import org.stellar.sdk.SorobanServer
import org.stellar.sdk.Util
import org.stellar.sdk.scval.Scv
import org.stellar.sdk.xdr.SCAddressType

class Sep45Tests : IntegrationTestBase(TestConfig()) {
  private var sep45Client: Sep45Client =
    Sep45Client(
      toml.getString("WEB_AUTH_FOR_CONTRACTS_ENDPOINT"),
      SorobanServer("https://soroban-testnet.stellar.org"),
      CLIENT_WALLET_SECRET,
      CLIENT_DOMAIN_SECRET,
    )
  private val jwtService =
    JwtService(
      config.env["secret.sep6.more_info_url.jwt_secret"],
      config.env["secret.sep10.jwt_secret"]!!,
      config.env["secret.sep45.jwt_secret"]!!,
      config.env["secret.sep24.interactive_url.jwt_secret"]!!,
      config.env["secret.sep24.more_info_url.jwt_secret"]!!,
      config.env["secret.callback_api.auth_secret"]!!,
      config.env["secret.platform_api.auth_secret"]!!,
      null,
    )

  private var webAuthDomain = toml.getString("WEB_AUTH_FOR_CONTRACTS_ENDPOINT")
  private var homeDomain = "http://${URI.create(webAuthDomain).authority}"

  @Test
  fun testChallengeSigningAndVerification() {
    val challenge =
      sep45Client.getChallenge(
        ChallengeRequest.builder()
          .account(CLIENT_SMART_WALLET_ACCOUNT)
          .homeDomain(homeDomain)
          .build()
      )
    Log.info("Challenge: ${GsonUtils.getInstance().toJson(challenge)}")

    val validationRequest = sep45Client.sign(challenge)
    Log.info("Validation request: ${GsonUtils.getInstance().toJson(validationRequest)}")

    val validationResponse = sep45Client.validate(validationRequest)
    Log.info("Validation response: ${GsonUtils.getInstance().toJson(validationResponse)}")

    val jwt = jwtService.decode(validationResponse.token, Sep45Jwt::class.java)

    assertEquals(homeDomain, jwt.homeDomain)
    assertEquals(CLIENT_SMART_WALLET_ACCOUNT, jwt.account)
    assertNotNull(jwt.jti)
    assertEquals(homeDomain, jwt.iss)
    assertNotNull(jwt.issuedAt)
    assertNotNull(jwt.expiresAt)
  }

  @Test
  fun testVerificationWithUnsignedEntry() {
    val challenge =
      sep45Client.getChallenge(
        ChallengeRequest.builder()
          .account(CLIENT_SMART_WALLET_ACCOUNT)
          .homeDomain(homeDomain)
          .build()
      )
    Log.info("Challenge: ${GsonUtils.getInstance().toJson(challenge)}")

    assertThrows<RuntimeException> {
      sep45Client.validate(
        ValidationRequest.builder().authorizationEntries(challenge.authorizationEntries).build()
      )
    }
  }

  @Test
  fun testVerificationWithBadServerSignature() {
    val challenge =
      sep45Client.getChallenge(
        ChallengeRequest.builder()
          .account(CLIENT_SMART_WALLET_ACCOUNT)
          .homeDomain(homeDomain)
          .build()
      )
    Log.info("Challenge: ${GsonUtils.getInstance().toJson(challenge)}")

    val validationRequest = sep45Client.sign(challenge)
    Log.info("Validation request: ${GsonUtils.getInstance().toJson(validationRequest)}")

    val tamperedEntries =
      SorobanAuthorizationEntryList.fromXdrBase64(validationRequest.authorizationEntries)
        .authorizationEntryList
        .map {
          val address = it.credentials.address.address
          if (
            address.discriminant.equals(SCAddressType.SC_ADDRESS_TYPE_ACCOUNT) &&
              KeyPair.fromXdrPublicKey(address.accountId.accountID)
                .equals(KeyPair.fromAccountId(toml.getString("SIGNING_KEY")))
          ) {
            val corruptSignature =
              Scv.toMap(
                LinkedHashMap(
                  mapOf(
                    Scv.toSymbol("public_key") to
                      Scv.toBytes(
                        Util.hexToBytes(
                          "d2b0e30a60aedad5a00b89ef371781900e805d1eb5bce945f649857281103e55"
                        )
                      ),
                    Scv.toSymbol("signature") to
                      Scv.toBytes(
                        Util.hexToBytes(
                          "35180b69a3fd15b435c19f63aea9f9e96d8f07e54529b073c659edab361c052fbb893f6d8d991c88614ba33b51fc7be17995f509711cfbfabd7500fe3f6e440c"
                        )
                      ),
                  )
                )
              )
            it.credentials.address.signature.vec = Scv.toVec(listOf(corruptSignature)).vec
            it
          } else {
            it
          }
        }

    assertThrows<RuntimeException> {
      sep45Client.validate(
        ValidationRequest.builder()
          .authorizationEntries(
            SorobanAuthorizationEntryList(tamperedEntries.toTypedArray()).toXdrBase64()
          )
          .build()
      )
    }
  }

  @Test
  fun testClientDomainVerification() {
    val challenge =
      sep45Client.getChallenge(
        ChallengeRequest.builder()
          .account(CLIENT_SMART_WALLET_ACCOUNT)
          .homeDomain(homeDomain)
          .clientDomain("wallet-server:8092")
          .build()
      )
    Log.info("Challenge: ${GsonUtils.getInstance().toJson(challenge)}")

    val validationRequest = sep45Client.sign(challenge, signWithClientDomain = true)
    Log.info("Validation request: ${GsonUtils.getInstance().toJson(validationRequest)}")

    val validationResponse = sep45Client.validate(validationRequest)
    Log.info("Validation response: ${GsonUtils.getInstance().toJson(validationResponse)}")

    val jwt = jwtService.decode(validationResponse.token, Sep45Jwt::class.java)

    assertEquals(homeDomain, jwt.homeDomain)
    assertEquals(CLIENT_SMART_WALLET_ACCOUNT, jwt.account)
    assertNotNull(jwt.jti)
    assertEquals(homeDomain, jwt.iss)
    assertNotNull(jwt.issuedAt)
    assertNotNull(jwt.expiresAt)
  }

  @Test
  fun testClientDomainVerificationWithMissingClientDomainSignature() {
    val challenge =
      sep45Client.getChallenge(
        ChallengeRequest.builder()
          .account(CLIENT_SMART_WALLET_ACCOUNT)
          .homeDomain(homeDomain)
          .clientDomain("wallet-server:8092")
          .build()
      )
    Log.info("Challenge: ${GsonUtils.getInstance().toJson(challenge)}")

    val validationRequest = sep45Client.sign(challenge, signWithClientDomain = false)

    assertThrows<RuntimeException> { sep45Client.validate(validationRequest) }
  }
}
