package org.stellar.anchor.platform.controller.custody;

import static org.stellar.anchor.platform.data.JdbcCustodyTransaction.PaymentType.PAYMENT;

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
import org.stellar.anchor.api.custody.CreateTransactionRefundRequest;
import org.stellar.anchor.platform.custody.CustodyTransactionService;

@RestController
public class CustodyTransactionController {

  private final CustodyTransactionService custodyTransactionService;

  public CustodyTransactionController(CustodyTransactionService custodyTransactionService) {
    this.custodyTransactionService = custodyTransactionService;
  }

  @SneakyThrows
  @CrossOrigin(origins = "*")
  @ResponseStatus(code = HttpStatus.OK)
  @RequestMapping(
      value = "/transactions",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.POST})
  public void createCustodyTransaction(@RequestBody CreateCustodyTransactionRequest request) {
    custodyTransactionService.create(request, PAYMENT);
  }

  @SneakyThrows
  @CrossOrigin(origins = "*")
  @ResponseStatus(code = HttpStatus.OK)
  @RequestMapping(
      value = "/transactions/{id}/payments",
      method = {RequestMethod.POST},
      produces = {MediaType.APPLICATION_JSON_VALUE},
      consumes = {MediaType.APPLICATION_JSON_VALUE})
  public CreateTransactionPaymentResponse createTransactionPayment(
      @PathVariable(name = "id") String txnId, @RequestBody String requestBody) {
    return custodyTransactionService.createPayment(txnId, requestBody);
  }

  @SneakyThrows
  @CrossOrigin(origins = "*")
  @ResponseStatus(code = HttpStatus.OK)
  @RequestMapping(
      value = "/transactions/{id}/refunds",
      method = {RequestMethod.POST},
      produces = {MediaType.APPLICATION_JSON_VALUE},
      consumes = {MediaType.APPLICATION_JSON_VALUE})
  public CreateTransactionPaymentResponse createTransactionRefund(
      @PathVariable(name = "id") String txnId,
      @RequestBody CreateTransactionRefundRequest refundRequest) {
    return custodyTransactionService.createRefund(txnId, refundRequest);
  }
}
