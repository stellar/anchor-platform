package org.stellar.reference.event.processor

import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.callback.GetCustomerResponse
import org.stellar.anchor.api.platform.CustomerUpdatedResponse
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.platform.GetTransactionsResponse
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind
import org.stellar.anchor.api.rpc.method.RpcMethod
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.api.shared.Amount
import org.stellar.anchor.api.shared.Customers
import org.stellar.anchor.api.shared.ProvidedCustomerField
import org.stellar.anchor.api.shared.StellarId
import org.stellar.reference.callbacks.customer.CustomerService
import org.stellar.reference.client.PaymentClient
import org.stellar.reference.client.PlatformClient
import org.stellar.reference.data.Config
import org.stellar.reference.data.NotifyOffchainFundsReceivedRequest
import org.stellar.reference.data.NotifyOnchainFundsSentRequest
import org.stellar.reference.data.RpcActionParamsRequest
import org.stellar.reference.data.SendEventRequest
import org.stellar.reference.data.SendEventRequestPayload
import org.stellar.reference.service.SepHelper
import org.stellar.sdk.responses.sorobanrpc.GetTransactionResponse as StellarTransactionResponse

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

  @Test
  fun `onCustomerUpdated retries and still advances the parked deposit when the first attempt fails`() =
    runBlocking {
      val txnId = newId()
      val customerId = UUID.randomUUID().toString()

      val platformClient = mockk<PlatformClient>()
      val customerService = mockk<CustomerService>(relaxed = true)
      val sepHelper = mockk<SepHelper>(relaxed = true)

      val pendingDeposit =
        GetTransactionResponse.builder()
          .id(txnId)
          .kind(Kind.DEPOSIT)
          .status(SepTransactionStatus.PENDING_CUSTOMER_INFO_UPDATE)
          .amountExpected(Amount.builder().amount("10").asset("iso4217:USD").build())
          .customers(
            Customers.builder()
              .sender(StellarId.builder().account("GSENDER").build())
              .receiver(StellarId.builder().account("GSENDER").build())
              .build()
          )
          .build()

      var attempts = 0
      coEvery { platformClient.getTransactions(any()) } answers
        {
          attempts++
          if (attempts == 1) throw RuntimeException("transient platform error")
          GetTransactionsResponse(listOf(pendingDeposit))
        }

      val acceptedCustomer =
        GetCustomerResponse.builder()
          .id(customerId)
          .status("ACCEPTED")
          .providedFields(
            listOf(
                "birth_date",
                "id_type",
                "id_country_code",
                "id_issue_date",
                "id_expiration_date",
                "id_number",
                "address",
              )
              .associateWith { ProvidedCustomerField.builder().build() }
          )
          .build()
      coEvery { customerService.getCustomer(any()) } returns acceptedCustomer

      val processor =
        Sep6EventProcessor(
          mockk<Config>(relaxed = true),
          platformClient,
          mockk<PaymentClient>(relaxed = true),
          customerService,
          sepHelper,
          customerUpdateRetryDelaySeconds = 0,
        )

      processor.onCustomerUpdated(
        SendEventRequest(
          id = UUID.randomUUID().toString(),
          type = "customer_updated",
          timestamp = "2026-10-01T00:00:00Z",
          payload =
            SendEventRequestPayload(
              transaction = null,
              quote = null,
              customer = CustomerUpdatedResponse(customerId),
            ),
        )
      )

      assertEquals(2, attempts)
      val method = slot<String>()
      coVerify(exactly = 1) { sepHelper.rpcAction(capture(method), any()) }
      assertEquals(RpcMethod.REQUEST_OFFCHAIN_FUNDS.toString(), method.captured)
    }

  private val usdc = "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"

  private fun pendingAnchorDeposit(id: String) =
    SendEventRequest(
      id = UUID.randomUUID().toString(),
      type = "transaction_status_changed",
      timestamp = "2026-10-01T00:00:00Z",
      payload =
        SendEventRequestPayload(
          transaction =
            GetTransactionResponse.builder()
              .id(id)
              .kind(Kind.DEPOSIT)
              .status(SepTransactionStatus.PENDING_ANCHOR)
              .destinationAccount("GDESTINATION")
              .amountExpected(Amount("100", usdc))
              .amountOut(Amount("100", usdc))
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

  private class DepositFixture {
    val sepHelper: SepHelper = mockk(relaxed = true)
    val paymentClient: PaymentClient = mockk()
    val onchainPayments = mutableMapOf<String, String>()
    val processor: Sep6EventProcessor

    init {
      val customerService: CustomerService = mockk()
      val allKyc = Sep6EventProcessor.requiredKyc + Sep6EventProcessor.depositRequiredKyc
      coEvery { customerService.getCustomer(any()) } returns
        GetCustomerResponse.builder()
          .providedFields(allKyc.associateWith { ProvidedCustomerField.builder().build() })
          .build()
      // each call to the ledger would produce a different payment id
      every { paymentClient.send(any(), any(), any(), any()) } returnsMany
        listOf("stellar-tx-1", "stellar-tx-2")
      every { paymentClient.getTransaction(any()) } returns
        mockk<StellarTransactionResponse> {
          every { status } returns StellarTransactionResponse.GetTransactionStatus.SUCCESS
        }
      processor =
        Sep6EventProcessor(
          mockk<Config>(relaxed = true),
          mockk<PlatformClient>(relaxed = true),
          paymentClient,
          customerService,
          sepHelper,
          onchainPayments,
        )
    }

    fun notifiedStellarIds(): List<String> {
      val params = mutableListOf<RpcActionParamsRequest>()
      coVerify { sepHelper.rpcAction(any(), capture(params)) }
      return params.filterIsInstance<NotifyOnchainFundsSentRequest>().map {
        it.stellarTransactionId
      }
    }
  }

  @Test
  fun `the first pending_anchor deposit event pays once and stores the payment id`() = runBlocking {
    val fixture = DepositFixture()
    val id = newId()

    fixture.processor.onTransactionStatusChanged(pendingAnchorDeposit(id))

    verify(exactly = 1) { fixture.paymentClient.send(any(), any(), any(), any()) }
    assertEquals("stellar-tx-1", fixture.onchainPayments[id])
  }

  @Test
  fun `a repeated pending_anchor deposit event does not pay again`() = runBlocking {
    val fixture = DepositFixture()
    val id = newId()

    fixture.processor.onTransactionStatusChanged(pendingAnchorDeposit(id))
    fixture.processor.onTransactionStatusChanged(pendingAnchorDeposit(id))

    verify(exactly = 1) { fixture.paymentClient.send(any(), any(), any(), any()) }
    assertEquals("stellar-tx-1", fixture.onchainPayments[id])
  }

  @Test
  fun `a repeated pending_anchor deposit event still notifies with the stored payment id`() =
    runBlocking {
      val fixture = DepositFixture()
      val id = newId()

      fixture.processor.onTransactionStatusChanged(pendingAnchorDeposit(id))
      fixture.processor.onTransactionStatusChanged(pendingAnchorDeposit(id))

      assertEquals(listOf("stellar-tx-1", "stellar-tx-1"), fixture.notifiedStellarIds())
    }
}
