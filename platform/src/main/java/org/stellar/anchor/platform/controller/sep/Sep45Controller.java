package org.stellar.anchor.platform.controller.sep;

import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.Log.errorEx;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.api.sep.SepExceptionResponse;
import org.stellar.anchor.api.sep.sep45.ChallengeRequest;
import org.stellar.anchor.api.sep.sep45.ChallengeResponse;
import org.stellar.anchor.api.sep.sep45.ValidationRequest;
import org.stellar.anchor.api.sep.sep45.ValidationResponse;
import org.stellar.anchor.platform.condition.OnAllSepsEnabled;
import org.stellar.anchor.sep45.Sep45Service;

@RequiredArgsConstructor
@RestController
@CrossOrigin
@RequestMapping("sep45")
@OnAllSepsEnabled(seps = {"sep45"})
public class Sep45Controller {
  private final Sep45Service sep45Service;

  @CrossOrigin
  @RequestMapping(
      value = "/auth",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET})
  public ChallengeResponse getChallenge(
      @RequestParam(name = "account") String account,
      @RequestParam(required = false, name = "home_domain") String homeDomain,
      @RequestParam(required = false, name = "client_domain") String clientDomain)
      throws AnchorException {
    debugF(
        "GET /auth account={} home_domain={} client_domain={}", account, homeDomain, clientDomain);
    ChallengeRequest challengeRequest =
        ChallengeRequest.builder()
            .account(account)
            .homeDomain(homeDomain)
            .clientDomain(clientDomain)
            .build();
    return sep45Service.getChallenge(challengeRequest);
  }

  @CrossOrigin
  @RequestMapping(
      value = "/auth",
      consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE},
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.POST})
  public ValidationResponse validate(
      @RequestParam(name = "authorization_entries") String authorizationEntries)
      throws AnchorException {
    debugF("POST /auth authorization_entries", authorizationEntries);
    return sep45Service.validate(
        ValidationRequest.builder().authorizationEntries(authorizationEntries).build());
  }

  @CrossOrigin
  @RequestMapping(
      value = "/auth",
      consumes = {MediaType.APPLICATION_JSON_VALUE},
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.POST})
  public ValidationResponse validate(@RequestBody ValidationRequest validationRequest)
      throws AnchorException {
    debugF("POST /auth {}", validationRequest);
    return sep45Service.validate(validationRequest);
  }

  @ExceptionHandler({
    SepException.class,
    BadRequestException.class,
  })
  @ResponseStatus(value = HttpStatus.BAD_REQUEST)
  public SepExceptionResponse handleSepValidationException(Exception ex) {
    errorEx(ex);
    return new SepExceptionResponse(ex.getMessage());
  }
}
