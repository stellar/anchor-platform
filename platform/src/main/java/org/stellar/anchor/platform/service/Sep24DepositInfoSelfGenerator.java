package org.stellar.anchor.platform.service;

import org.stellar.anchor.api.shared.SepDepositInfo;
import org.stellar.anchor.sep24.Sep24DepositInfoGenerator;
import org.stellar.anchor.sep24.Sep24Transaction;

public class Sep24DepositInfoSelfGenerator extends DepositInfoSelfGeneratorBase
    implements Sep24DepositInfoGenerator {
  @Override
  public SepDepositInfo generate(Sep24Transaction txn) {
    return new SepDepositInfo(txn.getToAccount(), generateMemoId());
  }
}
