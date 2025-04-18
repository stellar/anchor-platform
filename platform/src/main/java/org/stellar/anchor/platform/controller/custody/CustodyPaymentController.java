package org.stellar.anchor.platform.controller.custody;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.stellar.anchor.api.custody.GenerateDepositAddressResponse;
import org.stellar.anchor.api.exception.CustodyException;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.platform.custody.CustodyPaymentService;

@RestController
public class CustodyPaymentController {

  private final CustodyPaymentService<?> custodyPaymentService;

  public CustodyPaymentController(CustodyPaymentService<?> custodyPaymentService) {
    this.custodyPaymentService = custodyPaymentService;
  }

  @CrossOrigin(origins = "*")
  @ResponseStatus(code = HttpStatus.OK)
  @RequestMapping(
      value = "/assets/{assetId}/addresses",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.POST})
  public GenerateDepositAddressResponse generateDepositAddress(
      @PathVariable(name = "assetId") String assetId)
      throws CustodyException, InvalidConfigException {
    return custodyPaymentService.generateDepositAddress(assetId);
  }
}
