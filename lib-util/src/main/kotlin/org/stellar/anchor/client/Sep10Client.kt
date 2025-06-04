package org.stellar.anchor.client

import java.net.URL
import org.stellar.anchor.api.exception.SepNotAuthorizedException
import org.stellar.anchor.api.sep.sep10.ChallengeResponse
import org.stellar.anchor.api.sep.sep10.ValidationRequest
import org.stellar.anchor.api.sep.sep10.ValidationResponse
import org.stellar.anchor.util.OkHttpUtil
import org.stellar.anchor.util.StringHelper.json
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Network
import org.stellar.sdk.Sep10Challenge

class Sep10Client(
  private val endpoint: String,
  private val serverAccount: String,
  private val walletAccount: String,
  private val signingKeys: Array<String>
) : SepClient() {
  constructor(
    endpoint: String,
    serverAccount: String,
    walletAccount: String,
    signingSeed: String
  ) : this(endpoint, serverAccount, walletAccount, arrayOf(signingSeed))

  fun auth(homeDomain: String, memo: String? = null): String {
    // Call to get challenge
    val challenge = challenge(homeDomain, memo)
    // Sign challenge
    val txn = sign(challenge, signingKeys, serverAccount, homeDomain)
    // Get token from challenge
    return validate(ValidationRequest.of(txn))!!.token
  }

  fun challenge(homeDomain: String? = "", memo: String? = ""): ChallengeResponse {
    var url =
      String.format(
        "%s?account=%s",
        this.endpoint,
        walletAccount,
      )
    if (homeDomain.orEmpty().isNotEmpty()) {
      url += "&home_domain=$homeDomain"
    }
    if (memo.orEmpty().isNotEmpty()) {
      url += "&memo=$memo"
    }
    val responseBody = httpGet(url)
    return gson.fromJson(responseBody, ChallengeResponse::class.java)
  }

  fun challengeJson(): String {
    val url = String.format("%s?account=%s", this.endpoint, walletAccount)
    return httpGet(url)!!
  }

  private fun sign(
    challengeResponse: ChallengeResponse,
    signingKeys: Array<String>,
    serverAccount: String,
    homeDomain: String,
  ): String {
    val url = URL(endpoint)
    val webAuthDomain = url.authority
    val challengeTransaction =
      Sep10Challenge.readChallengeTransaction(
        challengeResponse.transaction,
        serverAccount,
        Network(challengeResponse.networkPassphrase),
        homeDomain,
        webAuthDomain
      )
    for (signingKey in signingKeys) {
      challengeTransaction.transaction.sign(KeyPair.fromSecretSeed(signingKey))
    }
    return challengeTransaction.transaction.toEnvelopeXdrBase64()
  }

  fun validate(validationRequest: ValidationRequest): ValidationResponse? {
    val request = OkHttpUtil.buildJsonPostRequest(this.endpoint, json(validationRequest))
    val response = client.newCall(request).execute()
    if (response.code != 200) {
      throw SepNotAuthorizedException("Error validating SEP10 transaction")
    }
    val responseBody = response.body!!.string()
    return gson.fromJson(responseBody, ValidationResponse::class.java)
  }
}
