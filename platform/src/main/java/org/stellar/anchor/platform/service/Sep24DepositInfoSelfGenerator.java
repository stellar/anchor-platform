package org.stellar.anchor.platform.service;

import org.stellar.anchor.api.shared.SepDepositInfo;
import org.stellar.anchor.sep24.Sep24DepositInfoGenerator;
import org.stellar.anchor.sep24.Sep24Transaction;

public class Sep24DepositInfoSelfGenerator extends DepositInfoSelfGeneratorBase
    implements Sep24DepositInfoGenerator {
  @Override
  public SepDepositInfo generate(Sep24Transaction txn) {
    if ("id".equalsIgnoreCase(txn.getMemoType()) && txn.getMemo() != null) {
      // If the memo type is "id", we use the memo as the deposit info.
      return new SepDepositInfo(txn.getToAccount(), txn.getMemo());
    }
    return new SepDepositInfo(txn.getToAccount(), generateMemoId());
  }
}
