package org.stellar.anchor.sep24;

import static org.stellar.anchor.util.MathHelper.decimal;
import static org.stellar.anchor.util.MathHelper.formatAmount;
import static org.stellar.anchor.util.MathHelper.sum;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.stellar.anchor.api.sep.AssetInfo;
import org.stellar.anchor.api.shared.Refunds;
import org.stellar.anchor.api.shared.SepRefunds;

@SuppressWarnings("unused")
public interface Sep24Refunds extends SepRefunds {
  String getAmountRefunded();

  void setAmountRefunded(String amountRefunded);

  String getAmountFee();

  void setAmountFee(String amountFee);

  List<Sep24RefundPayment> getRefundPayments();

  void setRefundPayments(List<Sep24RefundPayment> refundPayments);

  default void recalculateAmounts(AssetInfo assetInfo) {
    String amountFee = calculateAmount(assetInfo, Sep24RefundPayment::getFee);
    setAmountFee(amountFee);
    setAmountRefunded(
        formatAmount(
            sum(assetInfo, amountFee, calculateAmount(assetInfo, Sep24RefundPayment::getAmount))));
  }

  private String calculateAmount(AssetInfo assetInfo, Function<Sep24RefundPayment, String> func) {
    return formatAmount(
        getRefundPayments().stream()
            .map(func)
            .map(amount -> decimal(amount, assetInfo))
            .reduce(BigDecimal.ZERO, BigDecimal::add));
  }

  static Sep24Refunds of(Refunds platformApiRefunds, Sep24TransactionStore factory) {
    if (platformApiRefunds == null) {
      return null;
    }

    Sep24Refunds refunds = factory.newRefunds();
    refunds.setAmountRefunded(platformApiRefunds.getAmountRefunded().getAmount());
    refunds.setAmountFee(platformApiRefunds.getAmountFee().getAmount());

    ArrayList<Sep24RefundPayment> payments =
        Arrays.stream(platformApiRefunds.getPayments())
            .map(refundPayment -> Sep24RefundPayment.of(refundPayment, factory))
            .collect(Collectors.toCollection(ArrayList::new));
    refunds.setRefundPayments(payments);

    return refunds;
  }
}
