package org.stellar.reference.event.processor

import io.mockk.Called
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind
import org.stellar.anchor.api.rpc.method.RpcMethod
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.api.shared.Customers
import org.stellar.anchor.api.shared.StellarId
import org.stellar.reference.callbacks.customer.CustomerService
import org.stellar.reference.client.PaymentClient
import org.stellar.reference.client.PlatformClient
import org.stellar.reference.data.Config
import org.stellar.reference.data.NotifyOffchainFundsReceivedRequest
import org.stellar.reference.data.RpcActionParamsRequest
import org.stellar.reference.data.SendEventRequest
import org.stellar.reference.data.SendEventRequestPayload
import org.stellar.reference.service.SepHelper

class Sep6EventProcessorTest {
  private val sepHelper: SepHelper = mockk(relaxed = true)
  private val customerService: CustomerService = mockk(relaxed = true)
  private val processor =
    Sep6EventProcessor(
      mockk<Config>(relaxed = true),
      mockk<PlatformClient>(relaxed = true),
      mockk<PaymentClient>(relaxed = true),
      customerService,
      sepHelper,
    )

  // The opt-out registry is process-wide, so every test uses a fresh id.
  private fun newId() = UUID.randomUUID().toString()

  private fun depositEvent(id: String, status: SepTransactionStatus, type: String) =
    SendEventRequest(
      id = UUID.randomUUID().toString(),
      type = type,
      timestamp = "2026-10-01T00:00:00Z",
      payload =
        SendEventRequestPayload(
          transaction =
            GetTransactionResponse.builder()
              .id(id)
              .kind(Kind.DEPOSIT)
              .status(status)
              .customers(
                Customers.builder()
                  .sender(StellarId.builder().account("GSENDER").build())
                  .receiver(StellarId.builder().account("GSENDER").build())
                  .build()
              )
              .build(),
          quote = null,
          customer = null,
        ),
    )

  @Test
  fun `a registered transaction gets no RPC on a status change`() = runBlocking {
    val id = newId()
    Sep6EventProcessor.skipAutoAdvance(id)

    processor.onTransactionStatusChanged(
      depositEvent(
        id,
        SepTransactionStatus.PENDING_USR_TRANSFER_START,
        "transaction_status_changed"
      )
    )

    coVerify(exactly = 0) { sepHelper.rpcAction(any(), any()) }
  }

  @Test
  fun `a registered transaction gets no RPC and no customer lookup on creation`() = runBlocking {
    val id = newId()
    Sep6EventProcessor.skipAutoAdvance(id)

    processor.onTransactionCreated(
      depositEvent(id, SepTransactionStatus.INCOMPLETE, "transaction_created")
    )

    coVerify(exactly = 0) { sepHelper.rpcAction(any(), any()) }
    verify(exactly = 0) { customerService wasNot Called }
  }

  @Test
  fun `an unregistered deposit at pending_user_transfer_start still gets notify_offchain_funds_received`() =
    runBlocking {
      val id = newId()
      val method = slot<String>()
      val params = slot<RpcActionParamsRequest>()

      processor.onTransactionStatusChanged(
        depositEvent(
          id,
          SepTransactionStatus.PENDING_USR_TRANSFER_START,
          "transaction_status_changed",
        )
      )

      coVerify(exactly = 1) { sepHelper.rpcAction(capture(method), capture(params)) }
      assertEquals(RpcMethod.NOTIFY_OFFCHAIN_FUNDS_RECEIVED.toString(), method.captured)
      assertEquals(id, (params.captured as NotifyOffchainFundsReceivedRequest).transactionId)
    }
}
