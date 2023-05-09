package org.stellar.anchor.platform.controller.custody;

import lombok.SneakyThrows;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.stellar.anchor.api.custody.CreateCustodyTransactionRequest;
import org.stellar.anchor.api.custody.CreateTransactionPaymentResponse;
import org.stellar.anchor.platform.custody.CustodyTransactionService;

@RestController
public class CustodyTransactionController {

  private final CustodyTransactionService custodyTransactionService;

  public CustodyTransactionController(CustodyTransactionService custodyTransactionService) {
    this.custodyTransactionService = custodyTransactionService;
  }

  @CrossOrigin(origins = "*")
  @ResponseStatus(code = HttpStatus.OK)
  @RequestMapping(
      value = "/transactions",
      method = {RequestMethod.POST})
  public void createCustodyTransaction(@RequestBody CreateCustodyTransactionRequest request) {
    custodyTransactionService.create(request);
  }

  @SneakyThrows
  @CrossOrigin(origins = "*")
  @ResponseStatus(code = HttpStatus.OK)
  @RequestMapping(
      value = "/transactions/{id}/payments",
      method = {RequestMethod.POST},
      consumes = {MediaType.APPLICATION_JSON_VALUE})
  public CreateTransactionPaymentResponse createTransactionPayment(
      @PathVariable(name = "id") String txnId, @RequestBody String requestBody) {
    return custodyTransactionService.createPayment(txnId, requestBody);
  }
}
