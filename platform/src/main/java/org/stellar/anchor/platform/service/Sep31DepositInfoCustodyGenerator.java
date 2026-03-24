package org.stellar.anchor.platform.service;

import org.stellar.anchor.api.custody.GenerateDepositAddressResponse;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.shared.SepDepositInfo;
import org.stellar.anchor.platform.apiclient.CustodyApiClient;
import org.stellar.anchor.sep31.Sep31DepositInfoGenerator;
import org.stellar.anchor.sep31.Sep31Transaction;

public class Sep31DepositInfoCustodyGenerator implements Sep31DepositInfoGenerator {

  private final CustodyApiClient custodyApiClient;

  public Sep31DepositInfoCustodyGenerator(CustodyApiClient custodyApiClient) {
    this.custodyApiClient = custodyApiClient;
  }

  @Override
  public SepDepositInfo generate(Sep31Transaction txn) throws AnchorException {
    GenerateDepositAddressResponse depositAddress =
        custodyApiClient.generateDepositAddress(txn.getAmountInAsset());
    return new SepDepositInfo(depositAddress.getAddress(), depositAddress.getMemo());
  }
}
