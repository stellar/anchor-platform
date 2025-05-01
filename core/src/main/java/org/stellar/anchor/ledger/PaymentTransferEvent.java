package org.stellar.anchor.ledger;

import lombok.Builder;
import lombok.Data;

/**
 * This class represents a payment transfer event. It contains the information of a CAP-0067 payment
 * event. For more information about CAP-0067, see: <a
 * href="https://github.com/stellar/stellar-protocol/blob/master/core/cap-0067.md#payment">...</a>
 * contract: asset, topics: ["transfer", from:Address, to:Address, sep0011_asset:String], data:
 * amount:i128
 */
@Data
@Builder
public class PaymentTransferEvent {
  /** The from address of the payment transfer. */
  String from;

  /** The to address of the payment transfer. */
  String to;

  /** The asset of the payment transfer. */
  String sep11Asset;

  /** The XDR amount of the payment transfer. */
  Long amount;

  /** The transaction hash of the payment transfer. */
  String txHash;

  // the TOID used to identify the operation in the transaction.
  Long operationId;

  /**
   * The ledgerTransaction that contains the payment operation. Please note that the field can be
   * null unless the payment operation is a path payment operation.
   */
  LedgerTransaction ledgerTransaction;
}
