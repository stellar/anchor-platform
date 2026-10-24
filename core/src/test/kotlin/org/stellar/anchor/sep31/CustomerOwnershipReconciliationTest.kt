package org.stellar.anchor.sep31

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.TestConstants.Companion.TEST_ACCOUNT
import org.stellar.anchor.TestConstants.Companion.TEST_MEMO
import org.stellar.anchor.TestHelper
import org.stellar.anchor.api.callback.CustomerIntegration
import org.stellar.anchor.api.callback.GetCustomerResponse
import org.stellar.anchor.auth.WebAuthJwt

class CustomerOwnershipReconciliationTest {
  private val store = mockk<Sep31CustomerIdOwnerStore>(relaxed = true)
  private val customerIntegration = mockk<CustomerIntegration>()

  private lateinit var token: WebAuthJwt

  @BeforeEach
  fun setUp() {
    token = TestHelper.createWebAuthJwt(account = TEST_ACCOUNT, accountMemo = TEST_MEMO)
    token.clientName = "vibrant"
  }

  private fun reconcile(jwt: WebAuthJwt = token) =
    CustomerOwnershipReconciliation.tryReconcile(
      store,
      customerIntegration,
      "customer-1",
      jwt,
      "sep31-receiver"
    )

  @Test
  fun `tryReconcile hands the token key and memo to the store when the business server confirms the id`() {
    every { customerIntegration.getCustomer(any()) } returns
      GetCustomerResponse().apply { id = "customer-1" }
    every { store.getCreatorMemo("customer-1") } returns "222"
    every {
      store.reconcileLegacyKey("customer-1", "vibrant", "222", "vibrant:$TEST_ACCOUNT", TEST_MEMO)
    } returns true

    assertTrue(reconcile())
    verify(exactly = 1) {
      store.reconcileLegacyKey("customer-1", "vibrant", "222", "vibrant:$TEST_ACCOUNT", TEST_MEMO)
    }
  }

  @Test
  fun `tryReconcile hands a muxed token's muxed account and id to the store`() {
    val muxed = TestHelper.createMuxedWebAuthJwt(muxedId = 456L)
    muxed.clientName = "vibrant"
    every { customerIntegration.getCustomer(any()) } returns
      GetCustomerResponse().apply { id = "customer-1" }
    every { store.getCreatorMemo("customer-1") } returns null
    every {
      store.reconcileLegacyKey(
        "customer-1",
        "vibrant",
        null,
        "vibrant:${muxed.ownerAccount}",
        "456"
      )
    } returns true

    assertTrue(reconcile(muxed))
  }

  @Test
  fun `tryReconcile refuses and writes nothing when the business server returns a different id`() {
    every { customerIntegration.getCustomer(any()) } returns
      GetCustomerResponse().apply { id = "customer-2" }

    assertFalse(reconcile())
    verify(exactly = 0) { store.reconcileLegacyKey(any(), any(), any(), any(), any()) }
  }

  @Test
  fun `tryReconcile refuses and writes nothing when the business server returns null`() {
    every { customerIntegration.getCustomer(any()) } returns null

    assertFalse(reconcile())
    verify(exactly = 0) { store.reconcileLegacyKey(any(), any(), any(), any(), any()) }
  }

  @Test
  fun `tryReconcile refuses and writes nothing when the business server call throws`() {
    every { customerIntegration.getCustomer(any()) } throws RuntimeException("callback down")

    assertFalse(reconcile())
    verify(exactly = 0) { store.reconcileLegacyKey(any(), any(), any(), any(), any()) }
  }

  @Test
  fun `tryReconcile does not call the business server or the store when the token has no client name`() {
    token.clientName = null

    assertFalse(reconcile())
    verify(exactly = 0) { customerIntegration.getCustomer(any()) }
    verify(exactly = 0) { store.reconcileLegacyKey(any(), any(), any(), any(), any()) }
  }
}
