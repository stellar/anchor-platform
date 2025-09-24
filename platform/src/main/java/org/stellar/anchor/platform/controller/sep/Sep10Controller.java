package org.stellar.anchor.platform.controller.sep;

import static org.stellar.anchor.util.Log.*;

import java.io.IOException;
import java.net.URISyntaxException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.stellar.anchor.api.exception.*;
import org.stellar.anchor.api.sep.SepAuthorizationExceptionResponse;
import org.stellar.anchor.api.sep.SepExceptionResponse;
import org.stellar.anchor.api.sep.sep10.ChallengeRequest;
import org.stellar.anchor.api.sep.sep10.ChallengeResponse;
import org.stellar.anchor.api.sep.sep10.ValidationRequest;
import org.stellar.anchor.api.sep.sep10.ValidationResponse;
import org.stellar.anchor.platform.condition.ConditionalOnAllSepsEnabled;
import org.stellar.anchor.sep10.Sep10Service;
import org.stellar.sdk.InvalidSep10ChallengeException;

@RestController
@CrossOrigin(origins = "*")
@ConditionalOnAllSepsEnabled(seps = {"sep10"})
public class Sep10Controller {

  private final Sep10Service sep10Service;

  public Sep10Controller(Sep10Service sep10Service) {
    this.sep10Service = sep10Service;
  }

  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/auth",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET})
  public ChallengeResponse createChallenge(
      @RequestParam String account,
      @RequestParam(required = false) String memo,
      @RequestParam(required = false, name = "home_domain") String homeDomain,
      @RequestParam(required = false, name = "client_domain") String clientDomain,
      @RequestHeader(required = false, name = "Authorization") String authorization)
      throws SepException, BadRequestException {
    debugF(
        "GET /auth account={} memo={} home_domain={}, client_domain={}",
        account,
        memo,
        homeDomain,
        clientDomain);
    ChallengeRequest challengeRequest =
        ChallengeRequest.builder()
            .account(account)
            .memo(memo)
            .homeDomain(homeDomain)
            .clientDomain(clientDomain)
            .build();
    return sep10Service.createChallenge(challengeRequest, authorization);
  }

  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/auth",
      consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE},
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.POST})
  public ValidationResponse validateChallenge(@RequestParam String transaction)
      throws InvalidSep10ChallengeException, IOException, SepValidationException {
    debugF("POST /auth transaction={}", transaction);
    return validateChallenge(ValidationRequest.of(transaction));
  }

  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/auth",
      consumes = {MediaType.APPLICATION_JSON_VALUE},
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.POST})
  public ValidationResponse validateChallenge(
      @RequestBody(required = false) ValidationRequest validationRequest)
      throws InvalidSep10ChallengeException, IOException, SepValidationException {
    debug("POST /auth details:", validationRequest);
    return sep10Service.validateChallenge(validationRequest);
  }

  @ExceptionHandler({
    SepException.class,
    SepValidationException.class,
    InvalidSep10ChallengeException.class,
    URISyntaxException.class
  })
  @ResponseStatus(value = HttpStatus.BAD_REQUEST)
  public SepExceptionResponse handleSepValidationException(Exception ex) {
    errorEx(ex);
    return new SepExceptionResponse(ex.getMessage());
  }

  @ExceptionHandler({
    SepNotAuthorizedException.class,
  })
  @ResponseStatus(value = HttpStatus.FORBIDDEN)
  public SepAuthorizationExceptionResponse handleSepAuthorizationException(Exception ex) {
    errorEx(ex);
    return new SepAuthorizationExceptionResponse(ex.getMessage());
  }

  @ExceptionHandler({
    SepMissingAuthHeaderException.class,
  })
  @ResponseStatus(value = HttpStatus.UNAUTHORIZED)
  public SepExceptionResponse handleSepMissingAuthHeaderExceptionException(Exception ex) {
    errorEx(ex);
    return new SepExceptionResponse(ex.getMessage());
  }

  @ExceptionHandler(RestClientException.class)
  @ResponseStatus(value = HttpStatus.SERVICE_UNAVAILABLE)
  public SepExceptionResponse handleRestClientException(RestClientException ex) {
    errorEx(ex);
    return new SepExceptionResponse(ex.getMessage());
  }
}
