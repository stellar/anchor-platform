package org.stellar.anchor.platform.controller;

import static org.stellar.anchor.platform.controller.Sep10Helper.getSep10Token;

import javax.servlet.http.HttpServletRequest;
import lombok.SneakyThrows;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.stellar.anchor.dto.sep12.Sep12GetCustomerRequest;
import org.stellar.anchor.dto.sep12.Sep12GetCustomerResponse;
import org.stellar.anchor.dto.sep12.Sep12PutCustomerRequest;
import org.stellar.anchor.dto.sep12.Sep12PutCustomerResponse;
import org.stellar.anchor.exception.SepValidationException;
import org.stellar.anchor.sep10.JwtToken;
import org.stellar.anchor.sep12.Sep12Service;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/sep12")
public class Sep12Controller {
  private final Sep12Service sep12Service;

  Sep12Controller(Sep12Service sep12Service) {
    this.sep12Service = sep12Service;
  }

  @SneakyThrows
  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/customer",
      method = {RequestMethod.GET})
  public Sep12GetCustomerResponse getCustomer(
      HttpServletRequest request,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String id,
      @RequestParam(required = false) String account,
      @RequestParam(required = false) String memo,
      @RequestParam(required = false, name = "memo_type") String memoType,
      @RequestParam(required = false) String lang) {
    JwtToken jwtToken = getSep10Token(request);
    Sep12GetCustomerRequest getCustomerRequest =
        Sep12GetCustomerRequest.builder()
            .type(type)
            .id(id)
            .account(account)
            .memo(memo)
            .memoType(memoType)
            .lang(lang)
            .build();

    return sep12Service.getCustomer(jwtToken, getCustomerRequest);
  }

  @SneakyThrows
  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/customer",
      consumes = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.PUT})
  public Sep12PutCustomerResponse putCustomer(
      HttpServletRequest request, @RequestBody Sep12PutCustomerRequest putCustomerRequest)
      throws SepValidationException {
    JwtToken jwtToken = getSep10Token(request);
    return sep12Service.putCustomer(jwtToken, putCustomerRequest);
  }
}
