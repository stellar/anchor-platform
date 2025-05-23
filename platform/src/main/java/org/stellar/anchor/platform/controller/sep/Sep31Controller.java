package org.stellar.anchor.platform.controller.sep;

import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.Log.errorEx;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.stellar.anchor.api.asset.Sep31Info.Fields;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.Sep31CustomerInfoNeededException;
import org.stellar.anchor.api.exception.Sep31MissingFieldException;
import org.stellar.anchor.api.sep.sep31.*;
import org.stellar.anchor.auth.WebAuthJwt;
import org.stellar.anchor.platform.condition.OnAllSepsEnabled;
import org.stellar.anchor.sep31.Sep31Service;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("sep31")
@OnAllSepsEnabled(seps = {"sep31"})
public class Sep31Controller {
  private final Sep31Service sep31Service;

  public Sep31Controller(Sep31Service sep31Service) {
    this.sep31Service = sep31Service;
  }

  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/info",
      method = {RequestMethod.GET})
  public Sep31InfoResponse getInfo() {
    debugF("GET /info");
    return sep31Service.getInfo();
  }

  @CrossOrigin(origins = "*")
  @ResponseStatus(code = HttpStatus.CREATED)
  @RequestMapping(
      value = "/transactions",
      consumes = {MediaType.APPLICATION_JSON_VALUE},
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.POST})
  public Sep31PostTransactionResponse postTransaction(
      HttpServletRequest servletRequest, @RequestBody Sep31PostTransactionRequest request)
      throws AnchorException {
    WebAuthJwt webAuthJwt = WebAuthJwtHelper.getToken(servletRequest);
    debugF("POST /transactions request={}", request);
    return sep31Service.postTransaction(webAuthJwt, request);
  }

  @CrossOrigin(origins = "*")
  @ResponseStatus(code = HttpStatus.OK)
  @RequestMapping(
      value = "/transactions/{id}",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET})
  public Sep31GetTransactionResponse getTransaction(
      HttpServletRequest ignoredServletRequest, @PathVariable(name = "id") String txnId)
      throws AnchorException {
    debugF("GET /transactions id={}", txnId);
    return sep31Service.getTransaction(txnId);
  }

  @CrossOrigin(origins = "*")
  @ResponseStatus(code = HttpStatus.OK)
  @RequestMapping(
      value = "/transactions/{id}",
      consumes = {MediaType.APPLICATION_JSON_VALUE},
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.PATCH})
  public Sep31GetTransactionResponse patchTransaction(
      HttpServletRequest ignoredServletRequest,
      @PathVariable(name = "id") String txnId,
      @RequestBody Sep31PatchTransactionRequest request)
      throws AnchorException {
    debugF("PATCH /transactions id={} request={}", txnId, request);
    return sep31Service.patchTransaction(request);
  }

  @ExceptionHandler(Sep31MissingFieldException.class)
  @ResponseStatus(value = HttpStatus.BAD_REQUEST)
  public Sep31MissingFieldResponse handleMissingField(Sep31MissingFieldException smfex) {
    errorEx(smfex);
    return Sep31MissingFieldResponse.from(smfex);
  }

  @ExceptionHandler(Sep31CustomerInfoNeededException.class)
  @ResponseStatus(value = HttpStatus.BAD_REQUEST)
  public Sep31CustomerInfoNeededResponse handleCustomerInfoNeeded(
      Sep31CustomerInfoNeededException scinex) {
    errorEx(scinex);
    return new Sep31CustomerInfoNeededResponse(scinex.getType());
  }

  public static class Sep31MissingFieldResponse {
    String error;
    Fields fields;

    public static Sep31MissingFieldResponse from(Sep31MissingFieldException exception) {
      Sep31MissingFieldResponse instance = new Sep31MissingFieldResponse();
      instance.error = "transaction_info_needed";
      instance.fields = exception.getMissingFields();

      return instance;
    }
  }

  @Data
  static class Sep31CustomerInfoNeededResponse {
    final String error;
    final String type;

    public Sep31CustomerInfoNeededResponse(String type) {
      this.error = "customer_info_needed";
      this.type = type;
    }
  }
}
