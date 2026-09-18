package org.stellar.anchor.util

import io.mockk.every
import io.mockk.mockkStatic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.LockAndMockStatic
import org.stellar.anchor.TestHelper
import org.stellar.anchor.api.exception.BadRequestException
import org.stellar.anchor.api.exception.InvalidConfigException
import org.stellar.sdk.xdr.MemoType

internal class SepHelperTest {
  @Test
  fun `test webAuthTokenIdentity for a plain account with no memo`() {
    val token = TestHelper.createWebAuthJwt(TestHelper.TEST_ACCOUNT)
    val identity = SepHelper.webAuthTokenIdentity(token)
    assertEquals(TestHelper.TEST_ACCOUNT, identity.account())
    assertEquals(null, identity.memo())
    assertEquals(null, identity.memoType())
  }

  @Test
  fun `test webAuthTokenIdentity for an account with a memo`() {
    val token = TestHelper.createWebAuthJwt(TestHelper.TEST_ACCOUNT, TestHelper.TEST_MEMO)
    val identity = SepHelper.webAuthTokenIdentity(token)
    assertEquals(TestHelper.TEST_ACCOUNT, identity.account())
    assertEquals(TestHelper.TEST_MEMO, identity.memo())
    assertEquals("id", identity.memoType())
  }

  @Test
  fun `test webAuthTokenIdentity for a muxed account is the muxed address alone, with no separate memo`() {
    val token = TestHelper.createMuxedWebAuthJwt(TestHelper.TEST_ACCOUNT, muxedId = 42L)
    val identity = SepHelper.webAuthTokenIdentity(token)
    assertEquals(token.muxedAccount, identity.account())
    assert(identity.account().startsWith("M"))
    assertEquals(null, identity.memo())
    assertEquals(null, identity.memoType())
  }

  @Test
  fun `test webAuthTokenIdentity distinguishes two different accounts`() {
    val victim =
      SepHelper.webAuthTokenIdentity(TestHelper.createWebAuthJwt(TestHelper.TEST_ACCOUNT))
    val attacker =
      SepHelper.webAuthTokenIdentity(
        TestHelper.createWebAuthJwt("GBLGJA4TUN5XOGTV6WO2BWYUI2OZR5GYQ5PDPCRMQ5XEPJOYWB2X4CJO")
      )
    assert(victim != attacker)
  }

  @Test
  fun `test webAuthTokenIdentity rejects a null token`() {
    assertThrows<BadRequestException> { SepHelper.webAuthTokenIdentity(null) }
  }

  @Test
  fun `test memoType conversion`() {
    assert(SepHelper.memoTypeString(MemoType.MEMO_ID).equals("id"))
    assert(SepHelper.memoTypeString(MemoType.MEMO_HASH).equals("hash"))
    assert(SepHelper.memoTypeString(MemoType.MEMO_TEXT).equals("text"))
    assert(SepHelper.memoTypeString(MemoType.MEMO_NONE).equals("none"))
    assert(SepHelper.memoTypeString(MemoType.MEMO_RETURN).equals("return"))
  }

  @ParameterizedTest
  @CsvSource(
    value =
      [
        "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG,",
        "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG:1234,1234",
        "MCEO75Y6YKE53HM6N46IJYH3LK3YYFZ4QWGNUKCSSIQSH3KOAD7BEAAAAAAAAAAAPNT2W,"
      ]
  )
  fun `test valid stellar account`(strAccount: String, expectedMemo: String?) {
    val gotMemo = SepHelper.getAccountMemo(strAccount)
    assertEquals(gotMemo, expectedMemo)
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "ABC",
        "ABC:123",
        "GCHU3RZAECOKGM2YAJLQIIYB2ZPLMFTTGN5D3XZNX4RDOEERVLXO7H__",
        "MCEO75Y6YKE53HM6N46IJYH3LK3YYFZ4QWGNUKCSSIQSH3KOAD7BEAAAAAAAAAAAPNT2W___",
        "AMCEO75Y6YKE53HM6N46IJYH3LK3YYFZ4QWGNUKCSSIQSH3KOAD7BEAAAAAAAAAAAPNT2W"
      ]
  )
  fun `test invalid stellar account`(strAccount: String) {
    assertThrows<Exception> { SepHelper.getAccountMemo(strAccount) }
  }

  @Test
  @LockAndMockStatic([NetUtil::class])
  fun `readToml throws InvalidConfigException when fetching instance metadata instead of TOML`() {
    val mockUrl = "http://169.254.169.254/latest/meta-data/local-hostname"
    val instanceMetadataContent =
      """
        hostname: ec2-instance-hostname
        ami-id: ami-1234567890abcdef0
        instance-id: i-1234567890abcdef0
        instance-type: t2.micro
        """
        .trimIndent()
    mockkStatic(NetUtil::class)
    every { NetUtil.fetch(mockUrl) } returns instanceMetadataContent

    var exceptionThrown = false
    try {
      Sep1Helper.readToml(mockUrl)
    } catch (e: InvalidConfigException) {
      exceptionThrown = true
    }
    assertTrue(exceptionThrown, "InvalidConfigException was expected but not thrown")
  }
}
