package org.stellar.anchor.sep31

import io.mockk.every
import io.mockk.mockk
import java.math.BigInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.stellar.anchor.auth.Sep10Jwt
import org.stellar.anchor.client.ClientService
import org.stellar.anchor.client.CustodialClient
import org.stellar.anchor.client.NonCustodialClient
import org.stellar.sdk.KeyPair
import org.stellar.sdk.MuxedAccount

class OwnerKeyResolverTest {
  private val key1 = KeyPair.random().accountId
  private val key2 = KeyPair.random().accountId
  private val userAccount = KeyPair.random().accountId

  private val clientService = mockk<ClientService>()
  private val resolver = OwnerKeyResolver(clientService)

  private fun token(sub: String, clientName: String?): Sep10Jwt {
    val jwt = Sep10Jwt("iss", sub, 0, 1, "jti", null, null)
    jwt.clientName = clientName
    return jwt
  }

  private fun custodial(name: String) =
    CustodialClient.builder().name(name).signingKeys(setOf(key1, key2)).build()

  private fun muxed(accountId: String, id: Long) =
    MuxedAccount(accountId, BigInteger.valueOf(id)).address

  @Test
  fun `custodial client owner key is the client name under every signing key`() {
    every { clientService.getClientConfigByName("vibrant") } returns custodial("vibrant")

    val first = token("$key1:42", "vibrant")
    val second = token("$key2:42", "vibrant")

    assertEquals("vibrant", resolver.ownerKey(first))
    assertEquals("vibrant", resolver.ownerKey(second))
    assertEquals(first.ownerMemo, second.ownerMemo)
  }

  @Test
  fun `custodial muxed token gives the client name and the muxed id as memo`() {
    every { clientService.getClientConfigByName("vibrant") } returns custodial("vibrant")

    val first = token(muxed(key1, 42), "vibrant")
    val second = token(muxed(key2, 42), "vibrant")

    assertEquals("vibrant", resolver.ownerKey(first))
    assertEquals("vibrant", resolver.ownerKey(second))
    assertEquals("42", first.ownerMemo)
    assertEquals("42", second.ownerMemo)
  }

  @Test
  fun `custodial end users stay apart through the memo and clients through the name`() {
    every { clientService.getClientConfigByName("vibrant") } returns custodial("vibrant")
    every { clientService.getClientConfigByName("other") } returns custodial("other")

    val memo42 = token("$key1:42", "vibrant")
    val memo43 = token("$key1:43", "vibrant")
    val otherClient = token("$key1:42", "other")

    assertEquals("vibrant", resolver.ownerKey(memo42))
    assertEquals("vibrant", resolver.ownerKey(memo43))
    assertEquals("other", resolver.ownerKey(otherClient))
    assertEquals("42", memo42.ownerMemo)
    assertEquals("43", memo43.ownerMemo)
  }

  @Test
  fun `non-custodial client keeps the per-account key`() {
    every { clientService.getClientConfigByName("wallet") } returns
      NonCustodialClient.builder().name("wallet").domains(setOf("wallet.org")).build()

    val alice = token(userAccount, "wallet")
    val bob = token(key1, "wallet")

    assertEquals("wallet:$userAccount", resolver.ownerKey(alice))
    assertEquals("wallet:$key1", resolver.ownerKey(bob))
    assertEquals(alice.ownerKey, resolver.ownerKey(alice))
  }

  @Test
  fun `token without a client name keeps the bare account`() {
    assertEquals(userAccount, resolver.ownerKey(token(userAccount, null)))
  }

  @Test
  fun `unregistered client name keeps the per-account key`() {
    every { clientService.getClientConfigByName("ghost") } returns null

    assertEquals("ghost:$key1", resolver.ownerKey(token(key1, "ghost")))
  }

  @Test
  fun `registry failure keeps the per-account key and does not fail the request`() {
    every { clientService.getClientConfigByName("vibrant") } throws RuntimeException("db down")

    assertEquals("vibrant:$key1", resolver.ownerKey(token(key1, "vibrant")))
  }
}
