package org.stellar.anchor.platform.service;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.stellar.anchor.api.custody.GenerateDepositAddressResponse;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.shared.SepDepositInfo;
import org.stellar.anchor.platform.apiclient.CustodyApiClient;
import org.stellar.anchor.sep6.Sep6DepositInfoGenerator;
import org.stellar.anchor.sep6.Sep6Transaction;

@RequiredArgsConstructor
public class Sep6DepositInfoCustodyGenerator implements Sep6DepositInfoGenerator {
  @NonNull private final CustodyApiClient custodyApiClient;

  @Override
  public SepDepositInfo generate(Sep6Transaction txn) throws AnchorException {
    GenerateDepositAddressResponse depositAddress =
        custodyApiClient.generateDepositAddress(txn.getAmountInAsset());
    return new SepDepositInfo(depositAddress.getAddress(), depositAddress.getMemo());
  }
}
