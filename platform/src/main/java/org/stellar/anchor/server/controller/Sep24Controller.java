package org.stellar.anchor.server.controller;

import static org.stellar.anchor.filter.BaseTokenFilter.JWT_TOKEN;
import static org.stellar.anchor.util.Log.*;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.stellar.anchor.dto.SepExceptionResponse;
import org.stellar.anchor.dto.sep24.*;
import org.stellar.anchor.exception.SepException;
import org.stellar.anchor.exception.SepNotFoundException;
import org.stellar.anchor.exception.SepValidationException;
import org.stellar.anchor.sep10.JwtToken;
import org.stellar.anchor.sep24.Sep24Service;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/sep24")
public class Sep24Controller {
  private final Sep24Service sep24Service;

  Sep24Controller(Sep24Service sep24Service) {
    this.sep24Service = sep24Service;
  }

  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/info",
      method = {RequestMethod.GET})
  public InfoResponse getInfo() {
    debug("/info");
    return sep24Service.getInfo();
  }

  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/transactions/deposit/interactive",
      consumes = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.POST})
  public InteractiveTransactionResponse deposit(
      HttpServletRequest request, @RequestBody HashMap<String, String> requestData)
      throws SepException, MalformedURLException, URISyntaxException {
    debug("/deposit", requestData);
    JwtToken token = getSep10Token(request);
    String fullUrl = getFullRequestUrl(request);
    InteractiveTransactionResponse itr = sep24Service.deposit(fullUrl, token, requestData);
    info("interactive redirection:", itr);
    return itr;
  }

  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/transactions/deposit/interactive",
      method = {RequestMethod.POST},
      consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
  public InteractiveTransactionResponse depositAllType(HttpServletRequest request)
      throws SepException, MalformedURLException, URISyntaxException {
    HashMap<String, String> requestData = new HashMap<>();
    for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
      requestData.put(entry.getKey(), entry.getValue()[0]);
    }

    return deposit(request, requestData);
  }

  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/transactions/withdraw/interactive",
      consumes = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.POST})
  public InteractiveTransactionResponse withdraw(
      HttpServletRequest request, @RequestBody HashMap<String, String> requestData)
      throws SepException, MalformedURLException, URISyntaxException {
    debug("/withdraw", requestData);
    JwtToken token = getSep10Token(request);
    String fullUrl = getFullRequestUrl(request);
    InteractiveTransactionResponse itr = sep24Service.withdraw(fullUrl, token, requestData);
    info("interactive redirection:", itr);
    return itr;
  }

  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/transactions/withdraw/interactive",
      method = {RequestMethod.POST},
      consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
  public InteractiveTransactionResponse withdrawAllType(HttpServletRequest request)
      throws SepException, MalformedURLException, URISyntaxException {
    HashMap<String, String> requestData = new HashMap<>();
    for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
      requestData.put(entry.getKey(), entry.getValue()[0]);
    }

    return withdraw(request, requestData);
  }

  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/transactions",
      consumes = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET})
  public GetTransactionsResponse getTransactions(
      HttpServletRequest request, @RequestBody GetTransactionsRequest tr)
      throws SepException, MalformedURLException, URISyntaxException {
    debug("/transactions", tr);
    JwtToken token = getSep10Token(request);
    return sep24Service.findTransactions(token, tr);
  }

  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/transactions",
      consumes = {MediaType.ALL_VALUE},
      method = {RequestMethod.GET})
  public GetTransactionsResponse getTransactions(
      HttpServletRequest request,
      @RequestParam(value = "asset_code") String assetCode,
      @RequestParam(required = false, value = "kind") String kind,
      @RequestParam(required = false, value = "limit") Integer limit,
      @RequestParam(required = false, value = "paging_id") String pagingId,
      @RequestParam(required = false, value = "no_older_than") String noOlderThan)
      throws MalformedURLException, URISyntaxException, SepException {
    debugF(
        "/transactions asset_code={} kind={} limit={} no_older_than={} paging_id={}",
        assetCode,
        kind,
        limit,
        noOlderThan,
        pagingId);
    GetTransactionsRequest gtr =
        GetTransactionsRequest.of(assetCode, kind, limit, noOlderThan, pagingId);
    return getTransactions(request, gtr);
  }

  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/transaction",
      consumes = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET})
  public GetTransactionResponse getTransaction(
      HttpServletRequest request, @RequestBody GetTransactionRequest tr)
      throws SepException, IOException, URISyntaxException {
    debug("/transaction", tr);
    JwtToken token = getSep10Token(request);

    return sep24Service.findTransaction(token, tr);
  }

  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/transaction",
      consumes = {MediaType.ALL_VALUE},
      method = {RequestMethod.GET})
  public GetTransactionResponse getTransaction(
      HttpServletRequest request,
      @RequestParam(required = false, value = "id") String id,
      @RequestParam(required = false, value = "external_transaction_id")
          String externalTransactionId,
      @RequestParam(required = false, value = "stellar_transaction_id") String stellarTransactionId)
      throws SepException, IOException, URISyntaxException {
    debugF(
        "/transaction id={} external_transaction_id={} stellar_transaction_id={}",
        id,
        externalTransactionId,
        stellarTransactionId);
    GetTransactionRequest tr =
        new GetTransactionRequest(id, stellarTransactionId, externalTransactionId);
    return getTransaction(request, tr);
  }

  String getFullRequestUrl(HttpServletRequest request) {
    if (request.getQueryString() != null) {
      return request.getRequestURL() + "?" + request.getQueryString();
    }

    return request.getRequestURL().toString();
  }

  JwtToken getSep10Token(HttpServletRequest request) throws SepValidationException {
    JwtToken token = (JwtToken) request.getAttribute(JWT_TOKEN);
    if (token == null) {
      throw new SepValidationException(
          "missing sep10 jwt token. Make sure the sep10 filter is invoked before the controller");
    }
    return token;
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, SepValidationException.class})
  @ResponseStatus(value = HttpStatus.BAD_REQUEST)
  SepExceptionResponse handleValidation(Exception ex) {
    return new SepExceptionResponse(ex.getMessage());
  }

  @ExceptionHandler({SepNotFoundException.class})
  @ResponseStatus(value = HttpStatus.NOT_FOUND)
  SepExceptionResponse handleNotFound(Exception ex) {
    return new SepExceptionResponse(ex.getMessage());
  }
}
