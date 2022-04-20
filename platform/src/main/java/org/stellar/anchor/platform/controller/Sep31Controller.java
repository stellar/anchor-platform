package org.stellar.anchor.platform.controller;

import static org.stellar.anchor.platform.controller.Sep10Helper.getSep10Token;
import static org.stellar.anchor.util.Log.errorEx;

import javax.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.stellar.anchor.asset.AssetInfo.Sep31TxnFieldSpecs;
import org.stellar.anchor.dto.sep31.*;
import org.stellar.anchor.exception.AnchorException;
import org.stellar.anchor.sep10.JwtToken;
import org.stellar.anchor.sep31.Sep31Service;
import org.stellar.anchor.sep31.Sep31Service.Sep31CustomerInfoNeededException;
import org.stellar.anchor.sep31.Sep31Service.Sep31MissingFieldException;

@RestController
@RequestMapping("sep31")
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
    return sep31Service.getInfo();
  }

  @CrossOrigin(origins = "*")
  @ResponseStatus(code = HttpStatus.CREATED)
  @RequestMapping(
      value = "/transactions",
      consumes = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.POST})
  public Sep31PostTransactionResponse postTransaction(
      HttpServletRequest servletRequest, @RequestBody Sep31PostTransactionRequest request)
      throws AnchorException {
    JwtToken jwtToken = getSep10Token(servletRequest);
    return sep31Service.postTransaction(jwtToken, request);
  }

  @CrossOrigin(origins = "*")
  @ResponseStatus(code = HttpStatus.OK)
  @RequestMapping(
      value = "/transactions/{id}",
      method = {RequestMethod.GET})
  public Sep31GetTransactionResponse getTransaction(
      HttpServletRequest servletRequest, @PathVariable(name = "id") String txnId)
      throws AnchorException {
    JwtToken jwtToken = getSep10Token(servletRequest);
    return sep31Service.getTransaction(txnId);
  }

  @CrossOrigin(origins = "*")
  @ResponseStatus(code = HttpStatus.OK)
  @RequestMapping(
      value = "/transactions/{id}",
      method = {RequestMethod.PATCH})
  public Sep31GetTransactionResponse patchTransaction(
      HttpServletRequest servletRequest,
      @PathVariable(name = "id") String txnId,
      @RequestBody Sep31PatchTransactionRequest request)
      throws AnchorException {
    JwtToken jwtToken = getSep10Token(servletRequest);
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
    Sep31TxnFieldSpecs fields;

    public static Sep31MissingFieldResponse from(Sep31MissingFieldException exception) {
      Sep31MissingFieldResponse instance = new Sep31MissingFieldResponse();
      instance.error = "transaction_info_needed";
      instance.fields = exception.getMissingFields();

      return instance;
    }
  }

  private class Sep31CustomerInfoNeededResponse {
    String error;
    String type;

    public Sep31CustomerInfoNeededResponse(String type) {
      this.error = "customer_info_needed";
      this.type = type;
    }
  }
}
