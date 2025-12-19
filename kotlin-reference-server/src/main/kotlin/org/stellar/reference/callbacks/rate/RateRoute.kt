package org.stellar.reference.callbacks.rate

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.stellar.anchor.api.callback.GetRateRequest
import org.stellar.anchor.util.GsonUtils
import org.stellar.reference.di.AUTH_CONFIG_ENDPOINT

/**
 * Defines the routes related to the rate callback API. See
 * [Rate Callbacks](https://developers.stellar.org/docs/platforms/anchor-platform/api-reference/callbacks/get-rates).
 *
 * @param rateService the [RateService] to use to process the requests.
 */
fun Route.rate(rateService: RateService) {
  authenticate(AUTH_CONFIG_ENDPOINT) {
    get("/rate") {
      val request =
        GetRateRequest.builder()
          .type(call.parameters["type"]?.let { GetRateRequest.Type.valueOf(it.uppercase()) })
          .sellAsset(call.parameters["sell_asset"])
          .sellAmount(call.parameters["sell_amount"])
          .sellDeliveryMethod(call.parameters["sell_delivery_method"])
          .buyAsset(call.parameters["buy_asset"])
          .buyAmount(call.parameters["buy_amount"])
          .buyDeliveryMethod(call.parameters["buy_delivery_method"])
          .countryCode(call.parameters["country_code"])
          .expireAfter(call.parameters["expire_after"])
          .clientId(call.parameters["client_id"])
          .build()
      val response = GsonUtils.getInstance().toJson(rateService.getRate(request))
      call.respond(response)
    }
  }
}
