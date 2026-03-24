package org.stellar.anchor.platform.extendedtest.rpc

import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.platform.e2etest.Sep24End2EndTests
import org.stellar.anchor.platform.e2etest.Sep31End2EndTests
import org.stellar.anchor.platform.e2etest.Sep6End2EndTest

// use TEST_PROFILE_NAME = "rpc"
class CustodyRpcSep31End2EndTests : Sep31End2EndTests()

// use TEST_PROFILE_NAME = "rpc"
class CustodyRpcSep24End2EndTests : Sep24End2EndTests() {
  // These are to override the default expected statuses for RPC configuration
  override fun getExpectedDepositStatus(): List<Pair<AnchorEvent.Type, SepTransactionStatus>> {
    return listOf(
      AnchorEvent.Type.TRANSACTION_CREATED to SepTransactionStatus.INCOMPLETE,
      AnchorEvent.Type.TRANSACTION_STATUS_CHANGED to
        SepTransactionStatus.PENDING_USR_TRANSFER_START,
      AnchorEvent.Type.TRANSACTION_STATUS_CHANGED to SepTransactionStatus.PENDING_ANCHOR,
      AnchorEvent.Type.TRANSACTION_STATUS_CHANGED to SepTransactionStatus.COMPLETED
    )
  }

  override fun getExpectedWithdrawalStatus(): List<Pair<AnchorEvent.Type, SepTransactionStatus>> {
    return listOf(
      AnchorEvent.Type.TRANSACTION_CREATED to SepTransactionStatus.INCOMPLETE,
      AnchorEvent.Type.TRANSACTION_STATUS_CHANGED to
        SepTransactionStatus.PENDING_USR_TRANSFER_START,
      AnchorEvent.Type.TRANSACTION_STATUS_CHANGED to SepTransactionStatus.PENDING_ANCHOR,
      AnchorEvent.Type.TRANSACTION_STATUS_CHANGED to SepTransactionStatus.COMPLETED
    )
  }
}

// use TEST_PROFILE_NAME = "rpc"
class CustodyRpcSep6End2EndTests : Sep6End2EndTest()
