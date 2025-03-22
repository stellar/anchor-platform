package org.stellar.anchor.ledger;

// ID represents the total order of Ledgers, Transactions and
// Operations. This is an implementation of SEP-35:
// https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0035.md
//
// Operations within the stellar network have a total order, expressed by three
// pieces of information:  the ledger sequence the operation was validated in,
// the order which the operation's containing transaction was applied in
// that ledger, and the index of the operation within that parent transaction.
//
// We express this order by packing those three pieces of information into a
// single signed 64-bit number (we used a signed number for SQL compatibility).
//
// The follow diagram shows this format:
//
//	 0                   1                   2                   3
//	 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
//	+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
//	|                    Ledger Sequence Number                     |
//	+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
//	|     Transaction Application Order     |       Op Index        |
//	+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
//
// By component:
//
// Ledger Sequence: 32-bits
//
//	A complete ledger sequence number in which the operation was validated.
//
//	Expressed in network byte order.
//
// Transaction Application Order: 20-bits
//
//	The order that the transaction was applied within the ledger it was
//	validated.  Accommodates up to 1,048,575 transactions in a single ledger.
//
//	Expressed in network byte order.
//
// Operation Index: 12-bits
//
//	The index of the operation within its parent transaction. Accommodates up
//	to 4095 operations per transaction.
//
//	Expressed in network byte order.
//
// Note: This does not uniquely identify an object.  Given a ledger, it will
// share its id with its first transaction and the first operation of that
// transaction as well.  Given that this ID is only meant for ordering within a
// single type of object, the sharing of ids across object types seems
// acceptable.

public class TOID {

  public static final long LEDGER_MASK = (1L << 32) - 1;
  public static final int TRANSACTION_MASK = (1 << 20) - 1;
  public static final int OPERATION_MASK = (1 << 12) - 1;

  public static final int LEDGER_SHIFT = 32;
  public static final int TRANSACTION_SHIFT = 12;
  public static final int OPERATION_SHIFT = 0;

  public long ledgerSequence;
  public int transactionOrder;
  public int operationOrder;

  public TOID(long ledgerSequence, int transactionOrder, int operationOrder) {
    this.ledgerSequence = ledgerSequence;
    this.transactionOrder = transactionOrder;
    this.operationOrder = operationOrder;
  }

  public long encode() {
    if (ledgerSequence < 0) {
      throw new IllegalArgumentException("Invalid ledger sequence");
    }

    if (transactionOrder < 0) {
      throw new IllegalArgumentException("Invalid transaction order");
    }

    if (operationOrder < 0) {
      throw new IllegalArgumentException("Invalid operation order");
    }

    if (ledgerSequence > LEDGER_MASK) {
      throw new IllegalArgumentException("Ledger sequence overflow");
    }

    if (transactionOrder > TRANSACTION_MASK) {
      throw new IllegalArgumentException("Transaction order overflow");
    }

    if (operationOrder > OPERATION_MASK) {
      throw new IllegalArgumentException("Operation order overflow");
    }

    long result = 0;
    result |= (this.ledgerSequence & LEDGER_MASK) << LEDGER_SHIFT;
    result |= ((long) transactionOrder & TRANSACTION_MASK) << TRANSACTION_SHIFT;
    result |= ((long) operationOrder & OPERATION_MASK) << OPERATION_SHIFT;
    return result;
  }

  public static TOID decode(long id) {
    int ledgerSequence = (int) ((id >> LEDGER_SHIFT) & LEDGER_MASK);
    int transactionOrder = (int) ((id >> TRANSACTION_SHIFT) & TRANSACTION_MASK);
    int operationOrder = (int) ((id >> OPERATION_SHIFT) & OPERATION_MASK);

    return new TOID(ledgerSequence, transactionOrder, operationOrder);
  }

  @Override
  public String toString() {
    return String.valueOf(encode());
  }
}
