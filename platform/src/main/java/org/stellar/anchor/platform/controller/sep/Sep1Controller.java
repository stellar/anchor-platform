package org.stellar.anchor.platform.controller.sep;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.api.exception.SepNotFoundException;
import org.stellar.anchor.api.sep.SepExceptionResponse;
import org.stellar.anchor.config.Sep1Config;
import org.stellar.anchor.platform.condition.OnAllSepsEnabled;
import org.stellar.anchor.sep1.Sep1Service;

@RestController
@CrossOrigin(origins = "*")
@OnAllSepsEnabled(seps = {"sep1"})
public class Sep1Controller {
  private final Sep1Config sep1Config;
  private final Sep1Service sep1Service;

  public Sep1Controller(Sep1Config sep1Config, Sep1Service sep1Service) {
    this.sep1Config = sep1Config;
    this.sep1Service = sep1Service;
  }

  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET, RequestMethod.OPTIONS})
  public RedirectView landingPage() throws SepNotFoundException {
    if (!sep1Config.isEnabled()) {
      throw new SepNotFoundException("Not Found");
    }
    RedirectView redirectView = new RedirectView();
    redirectView.setUrl("/.well-known/stellar.toml");
    return redirectView;
  }

  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/.well-known/stellar.toml",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET, RequestMethod.OPTIONS})
  public ResponseEntity<String> getToml() throws SepException {
    if (!sep1Config.isEnabled()) {
      throw new SepNotFoundException("Not Found");
    }
    HttpHeaders headers = new HttpHeaders();
    headers.set("content-type", "text/plain");

    return ResponseEntity.ok().headers(headers).body(sep1Service.getToml());
  }

  @ExceptionHandler({SepNotFoundException.class})
  @ResponseStatus(value = HttpStatus.NOT_FOUND)
  SepExceptionResponse handleNotFound(Exception ex) {
    return new SepExceptionResponse(ex.getMessage());
  }
}
