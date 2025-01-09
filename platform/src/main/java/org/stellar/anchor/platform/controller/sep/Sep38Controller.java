package org.stellar.anchor.platform.controller.sep;

import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.Log.errorEx;

import com.google.gson.Gson;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.SneakyThrows;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.stellar.anchor.api.exception.SepValidationException;
import org.stellar.anchor.api.sep.SepExceptionResponse;
import org.stellar.anchor.api.sep.sep38.*;
import org.stellar.anchor.auth.Sep10Jwt;
import org.stellar.anchor.platform.condition.OnAllSepsEnabled;
import org.stellar.anchor.sep38.Sep38Service;
import org.stellar.anchor.util.GsonUtils;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/sep38")
@OnAllSepsEnabled(seps = {"sep38"})
public class Sep38Controller {
  private final Sep38Service sep38Service;
  private static final Gson gson = GsonUtils.getInstance();

  public Sep38Controller(Sep38Service sep38Service) {
    this.sep38Service = sep38Service;
  }

  // TODO: add integration tests

  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/info",
      method = {RequestMethod.GET})
  public InfoResponse getInfo() {
    debugF("GET /info");
    return sep38Service.getInfo();
  }

  @SneakyThrows
  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/prices",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET})
  public GetPricesResponse getPrices(
      @RequestParam(name = "sell_asset") String sellAssetName,
      @RequestParam(name = "sell_amount") String sellAmount,
      @RequestParam(name = "sell_delivery_method", required = false) String sellDeliveryMethod,
      @RequestParam(name = "buy_delivery_method", required = false) String buyDeliveryMethod,
      @RequestParam(name = "country_code", required = false) String countryCode) {
    debugF(
        "GET /prices sell_asset={} sell_amount={} sell_delivery_method={} "
            + "buyDeliveryMethod={} countryCode={}",
        sellAssetName,
        sellAmount,
        sellDeliveryMethod,
        buyDeliveryMethod,
        countryCode);
    return sep38Service.getPrices(
        sellAssetName, sellAmount, sellDeliveryMethod, buyDeliveryMethod, countryCode);
  }

  @SneakyThrows
  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/price",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET})
  public GetPriceResponse getPrice(
      HttpServletRequest request, @RequestParam Map<String, String> params) {
    debugF("GET /price params={}", params);
    Sep38GetPriceRequest getPriceRequest =
        gson.fromJson(gson.toJson(params), Sep38GetPriceRequest.class);
    Sep10Jwt sep10Jwt;
    try {
      sep10Jwt = Sep10Helper.getSep10Token(request);
    } catch (SepValidationException svex) {
      sep10Jwt = null;
    }
    return sep38Service.getPrice(sep10Jwt, getPriceRequest);
  }

  @SneakyThrows
  @CrossOrigin(origins = "*")
  @ResponseStatus(code = HttpStatus.CREATED)
  @RequestMapping(
      value = "/quote",
      consumes = {MediaType.APPLICATION_JSON_VALUE},
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.POST})
  public Sep38QuoteResponse postQuote(
      HttpServletRequest request, @RequestBody Sep38PostQuoteRequest postQuoteRequest) {
    Sep10Jwt sep10Jwt = Sep10Helper.getSep10Token(request);
    debugF("POSTS /quote request={}", postQuoteRequest);
    return sep38Service.postQuote(sep10Jwt, postQuoteRequest);
  }

  @SneakyThrows
  @CrossOrigin(origins = "*")
  @ResponseStatus(code = HttpStatus.OK)
  @RequestMapping(
      value = "/quote/{quote_id}",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET})
  public Sep38QuoteResponse getQuote(
      HttpServletRequest request, @PathVariable(name = "quote_id") String quoteId) {
    Sep10Jwt sep10Jwt = Sep10Helper.getSep10Token(request);
    debugF("GET /quote id={}", quoteId);
    return sep38Service.getQuote(sep10Jwt, quoteId);
  }

  @ExceptionHandler(RestClientException.class)
  @ResponseStatus(value = HttpStatus.SERVICE_UNAVAILABLE)
  public SepExceptionResponse handleRestClientException(RestClientException ex) {
    errorEx(ex);
    return new SepExceptionResponse(ex.getMessage());
  }
}
