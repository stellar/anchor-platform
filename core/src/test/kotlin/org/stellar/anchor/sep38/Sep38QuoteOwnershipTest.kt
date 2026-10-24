package org.stellar.anchor.sep38

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.anchor.TestHelper.Companion.TEST_ACCOUNT
import org.stellar.anchor.TestHelper.Companion.createMuxedWebAuthJwt
import org.stellar.anchor.TestHelper.Companion.createWebAuthJwt
import org.stellar.anchor.sep38.Sep38QuoteOwnership.Identity
import org.stellar.sdk.KeyPair
import org.stellar.sdk.MuxedAccount

class Sep38QuoteOwnershipTest {
  private val otherAccount = KeyPair.random().accountId
  private val mAccount = MuxedAccount(TEST_ACCOUNT, 7.toBigInteger()).address

  private fun quoteBy(account: String?, memo: String? = null, memoType: String? = null) =
    PojoSep38Quote().apply {
      creatorAccountId = account
      creatorMemo = memo
      creatorMemoType = memoType
    }

  @Test
  fun `identityOf a G account without a memo has no memo and no memo type`() {
    assertEquals(
      Identity(TEST_ACCOUNT, null, null),
      Sep38QuoteOwnership.identityOf(createWebAuthJwt())
    )
  }

  @Test
  fun `identityOf a G account with a memo reports the memo as an id memo`() {
    assertEquals(
      Identity(TEST_ACCOUNT, "123", "id"),
      Sep38QuoteOwnership.identityOf(createWebAuthJwt(accountMemo = "123")),
    )
  }

  @Test
  fun `identityOf a muxed account is the M address with no memo`() {
    assertEquals(
      Identity(mAccount, null, null),
      Sep38QuoteOwnership.identityOf(createMuxedWebAuthJwt(muxedId = 7)),
    )
  }

  @Test
  fun `identityOf a muxed account ignores an account memo on the same token`() {
    val token = createMuxedWebAuthJwt(muxedId = 7).apply { accountMemo = "123" }
    assertEquals(Identity(mAccount, null, null), Sep38QuoteOwnership.identityOf(token))
  }

  @Test
  fun `identityOf a token with an empty account is null`() {
    assertNull(Sep38QuoteOwnership.identityOf(createWebAuthJwt("")))
  }

  @Test
  fun `identityOf a null token is null`() {
    assertNull(Sep38QuoteOwnership.identityOf(null))
  }

  @Test
  fun `isCreatedBy the same G account without a memo`() {
    assertTrue(Sep38QuoteOwnership.isCreatedBy(quoteBy(TEST_ACCOUNT), createWebAuthJwt()))
  }

  @Test
  fun `isCreatedBy the same G account and memo`() {
    assertTrue(
      Sep38QuoteOwnership.isCreatedBy(
        quoteBy(TEST_ACCOUNT, "123", "id"),
        createWebAuthJwt(accountMemo = "123"),
      )
    )
  }

  @Test
  fun `isCreatedBy the same muxed account`() {
    assertTrue(
      Sep38QuoteOwnership.isCreatedBy(quoteBy(mAccount), createMuxedWebAuthJwt(muxedId = 7))
    )
  }

  @Test
  fun `isCreatedBy is false for another account`() {
    assertFalse(Sep38QuoteOwnership.isCreatedBy(quoteBy(otherAccount), createWebAuthJwt()))
  }

  @Test
  fun `isCreatedBy is false for the same account under another memo`() {
    assertFalse(
      Sep38QuoteOwnership.isCreatedBy(
        quoteBy(TEST_ACCOUNT, "123", "id"),
        createWebAuthJwt(accountMemo = "124"),
      )
    )
  }

  @Test
  fun `isCreatedBy is false when the creator has no memo and the caller has one`() {
    assertFalse(
      Sep38QuoteOwnership.isCreatedBy(quoteBy(TEST_ACCOUNT), createWebAuthJwt(accountMemo = "123"))
    )
  }

  @Test
  fun `isCreatedBy is false when the creator has a memo and the caller has none`() {
    assertFalse(
      Sep38QuoteOwnership.isCreatedBy(quoteBy(TEST_ACCOUNT, "123", "id"), createWebAuthJwt())
    )
  }

  @Test
  fun `isCreatedBy is false when the memo types differ`() {
    assertFalse(
      Sep38QuoteOwnership.isCreatedBy(
        quoteBy(TEST_ACCOUNT, "123", "text"),
        createWebAuthJwt(accountMemo = "123"),
      )
    )
  }

  @Test
  fun `isCreatedBy is false when the creator is the G account and the caller is a muxed account of it`() {
    assertFalse(
      Sep38QuoteOwnership.isCreatedBy(quoteBy(TEST_ACCOUNT), createMuxedWebAuthJwt(muxedId = 7))
    )
  }

  @Test
  fun `isCreatedBy is false for a null token`() {
    assertFalse(Sep38QuoteOwnership.isCreatedBy(quoteBy(TEST_ACCOUNT), null))
  }

  @Test
  fun `isCreatedBy is false when the quote has no creator`() {
    assertFalse(Sep38QuoteOwnership.isCreatedBy(quoteBy(null), createWebAuthJwt()))
  }
}
