package org.stellar.anchor.sep24;

import java.util.List;
import lombok.Data;

@Data
public class PojoSep24Refunds implements Sep24Refunds {
  String amountRefunded;
  String amountFee;
  List<Sep24RefundPayment> refundPayments;

  @Override
  public boolean hasRefundPayments() {
    return refundPayments != null;
  }
}
