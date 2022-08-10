package org.stellar.anchor.sep31;

import java.util.List;

public class RefundsBuilder {
  private final Refunds refunds;
  private final Sep31TransactionStore factory;

  public RefundsBuilder(Sep31TransactionStore factory) {
    this.factory = factory;
    refunds = factory.newRefunds();
  }

  public RefundsBuilder amountRefunded(String amountRefunded) {
    refunds.setAmountRefunded(amountRefunded);
    return this;
  }

  public RefundsBuilder amountFee(String amountFee) {
    refunds.setAmountFee(amountFee);
    return this;
  }

  public RefundsBuilder payments(List<RefundPayment> payments) {
    refunds.setRefundPayments(payments);
    return this;
  }

  public Refunds build() {
    return refunds;
  }
}
