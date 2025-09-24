package org.stellar.reference.model

import java.time.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Quote(
  val id: String,
  val price: String?,
  @SerialName("total_price") val totalPrice: String?,
  @SerialName("expires_at") @Contextual val expiresAt: Instant?,
  @SerialName("created_at") @Contextual val createdAt: Instant?,
  @SerialName("sell_asset") val sellAsset: String?,
  @SerialName("sell_amount") val sellAmount: String?,
  @SerialName("sell_delivery_method") val sellDeliveryMethod: String?,
  @SerialName("buy_asset") val buyAsset: String?,
  @SerialName("buy_amount") val buyAmount: String?,
  @SerialName("buy_delivery_method") val buyDeliveryMethod: String?,
  @SerialName("country_code") val countryCode: String?,
  @SerialName("client_id") val clientId: String?,
  val fee: FeeDetails?,
)

@Serializable
data class FeeDetails(val total: String?, val asset: String, val details: List<FeeDescription>?)

@Serializable
data class FeeDescription(val name: String, val description: String, val amount: String?)
