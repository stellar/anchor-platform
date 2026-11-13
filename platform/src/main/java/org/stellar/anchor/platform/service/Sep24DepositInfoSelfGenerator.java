package org.stellar.anchor.platform.service;

import org.stellar.anchor.api.shared.SepDepositInfo;
import org.stellar.anchor.sep24.Sep24DepositInfoGenerator;
import org.stellar.anchor.sep24.Sep24Transaction;

public class Sep24DepositInfoSelfGenerator extends DepositInfoSelfGeneratorBase
    implements Sep24DepositInfoGenerator {
  @Override
  public SepDepositInfo generate(Sep24Transaction txn) {
    // The routing memo identifies whose payment this is, so it is always issued by the server.
    // A memo the client sent with the request is never used: SEP-6 and SEP-31 mint theirs the same
    // way, and the three protocols share one memo namespace on the distribution account.
    return new SepDepositInfo(txn.getToAccount(), generateMemoId());
  }
}
