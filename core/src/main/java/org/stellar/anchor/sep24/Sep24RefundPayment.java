package org.stellar.anchor.sep24;

@SuppressWarnings("unused")
public interface Sep24RefundPayment {
  /**
   * The payment ID that can be used to identify the refund payment. This is either a Stellar
   * transaction hash or an off-chain payment identifier, such as a reference number provided to the
   * user when the refund was initiated. This id is not guaranteed to be unique.
   *
   * @return the ID field.
   */
  String getId();

  void setId(String id);

  /**
   * "stellar" or "external".
   *
   * @return the idType field.
   */
  String getIdType();

  void setIdType(String idType);

  /**
   * The amount sent back to the user for the payment identified by id, in units of amount_in_asset.
   *
   * @return the amount.
   */
  String getAmount();

  void setAmount(String amount);
  /**
   * The amount charged as a fee for processing the refund, in units of amount_in_asset.
   *
   * @return the fee.
   */
  String getFee();

  void setFee(String fee);

  static Sep24RefundPayment of(
      org.stellar.anchor.api.shared.RefundPayment platformApiRefundPayment,
      Sep24TransactionStore factory) {
    if (platformApiRefundPayment == null) {
      return null;
    }

    Sep24RefundPayment refundPayment = factory.newRefundPayment();
    refundPayment.setId(platformApiRefundPayment.getId());
    refundPayment.setIdType(platformApiRefundPayment.getIdType().toString());
    refundPayment.setAmount(platformApiRefundPayment.getAmount().getAmount());
    refundPayment.setFee(platformApiRefundPayment.getFee().getAmount());

    return refundPayment;
  }
}
