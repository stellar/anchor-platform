package org.stellar.anchor.client

import org.stellar.anchor.api.sep.sep45.ChallengeRequest
import org.stellar.anchor.api.sep.sep45.ChallengeResponse
import org.stellar.anchor.api.sep.sep45.ValidationRequest
import org.stellar.anchor.api.sep.sep45.ValidationResponse
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.OkHttpUtil
import org.stellar.anchor.xdr.SorobanAuthorizationEntryList
import org.stellar.sdk.Auth
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Network
import org.stellar.sdk.SorobanServer
import org.stellar.sdk.xdr.SCAddressType

class Sep45Client(
  private val endpoint: String,
  private val rpc: SorobanServer,
  private val walletSigningKey: String,
) : SepClient() {

  fun getChallenge(request: ChallengeRequest): ChallengeResponse {
    val params = mutableMapOf<String, String>()
    params["account"] = request.account ?: ""
    params["home_domain"] = request.homeDomain ?: ""
    params["client_domain"] = request.clientDomain ?: ""

    val queryString =
      params.filter { it.value.isNotEmpty() }.map { "${it.key}=${it.value}" }.joinToString("&")

    val url = "${this.endpoint}?$queryString"

    val response = httpGet(url)
    return gson.fromJson(response, ChallengeResponse::class.java)
  }

  fun sign(challengeResponse: ChallengeResponse): ValidationRequest {
    val authEntries =
      SorobanAuthorizationEntryList.fromXdrBase64(challengeResponse.authorizationEntries)
        .authorizationEntryList
    authEntries.forEach { println(it.toXdrBase64()) }
    val walletAuthEntry =
      authEntries.find {
        it.credentials.address.address.discriminant.equals(SCAddressType.SC_ADDRESS_TYPE_CONTRACT)
      }
        ?: throw RuntimeException("Contract auth entry not found in challenge response")

    val walletKeyPair = KeyPair.fromSecretSeed(walletSigningKey)

    val validUntilLedgerSeq = rpc.latestLedger.sequence + 1.toLong()
    val signedEntry =
      Auth.authorizeEntry(
        walletAuthEntry,
        walletKeyPair,
        validUntilLedgerSeq,
        Network(rpc.network.passphrase),
      )

    // Replace auth entry in the list
    val signedAuthEntries =
      authEntries.map {
        if (it.credentials.address.address == walletAuthEntry.credentials.address.address) {
          signedEntry
        } else {
          it
        }
      }

    return ValidationRequest.builder()
      .authorizationEntries(
        SorobanAuthorizationEntryList(signedAuthEntries.toTypedArray()).toXdrBase64()
      )
      .build()
  }

  fun validate(validationRequest: ValidationRequest): ValidationResponse {
    val request =
      OkHttpUtil.buildJsonPostRequest(
        this.endpoint,
        GsonUtils.getInstance().toJson(validationRequest),
      )
    val response = client.newCall(request).execute()
    if (!response.isSuccessful) {
      throw RuntimeException("Failed to validate challenge: ${response.body!!.string()}")
    }
    val responseBody = response.body!!.string()
    return gson.fromJson(responseBody, ValidationResponse::class.java)
  }
}
