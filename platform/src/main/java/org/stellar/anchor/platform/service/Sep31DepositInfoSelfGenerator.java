package org.stellar.anchor.platform.service;

import org.stellar.anchor.api.shared.SepDepositInfo;
import org.stellar.anchor.sep31.Sep31DepositInfoGenerator;
import org.stellar.anchor.sep31.Sep31Transaction;

public class Sep31DepositInfoSelfGenerator extends DepositInfoSelfGeneratorBase
    implements Sep31DepositInfoGenerator {
  @Override
  public SepDepositInfo generate(Sep31Transaction txn) {
    return new SepDepositInfo(txn.getToAccount(), generateMemoId());
  }
}
