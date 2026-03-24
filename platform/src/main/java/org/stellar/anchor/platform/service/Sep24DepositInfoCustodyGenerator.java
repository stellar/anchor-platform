package org.stellar.anchor.platform.service;

import org.stellar.anchor.api.custody.GenerateDepositAddressResponse;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.shared.SepDepositInfo;
import org.stellar.anchor.platform.apiclient.CustodyApiClient;
import org.stellar.anchor.sep24.Sep24DepositInfoGenerator;
import org.stellar.anchor.sep24.Sep24Transaction;

public class Sep24DepositInfoCustodyGenerator implements Sep24DepositInfoGenerator {

  private final CustodyApiClient custodyApiClient;

  public Sep24DepositInfoCustodyGenerator(CustodyApiClient custodyApiClient) {
    this.custodyApiClient = custodyApiClient;
  }

  @Override
  public SepDepositInfo generate(Sep24Transaction txn) throws AnchorException {
    GenerateDepositAddressResponse depositAddress =
        custodyApiClient.generateDepositAddress(txn.getAmountInAsset());
    return new SepDepositInfo(depositAddress.getAddress(), depositAddress.getMemo());
  }
}
