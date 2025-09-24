package org.stellar.anchor.sep31;

import static org.stellar.anchor.util.SepHelper.validateTransactionStatus;

import org.stellar.anchor.api.exception.BadRequestException;

public class Sep31Helper {
  public static boolean allAmountAvailable(Sep31Transaction txn) {
    return txn.getAmountIn() != null
        && txn.getAmountInAsset() != null
        && txn.getFeeDetails() != null
        && txn.getFeeDetails().getAsset() != null
        && txn.getAmountOut() != null
        && txn.getAmountOutAsset() != null;
  }

  public static void validateStatus(Sep31Transaction txn) throws BadRequestException {
    if (!validateTransactionStatus(txn.getStatus(), 31)) {
      throw new BadRequestException(
          String.format("'%s' is not a valid status of SEP31.", txn.getStatus()));
    }
  }
}
