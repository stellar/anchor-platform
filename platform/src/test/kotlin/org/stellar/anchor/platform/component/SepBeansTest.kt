package org.stellar.anchor.platform.component

import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.config.*
import org.stellar.anchor.platform.component.sep.SepBeans

class SepBeansTest {
  @MockK(relaxed = true) private lateinit var secretConfig: SecretConfig
  @MockK(relaxed = true) lateinit var sep38Config: Sep38Config
  private lateinit var jwtService: JwtService
  private lateinit var sepBeans: SepBeans

  @BeforeEach
  fun setUp() {
    secretConfig = mockk(relaxed = true)
    sep38Config = mockk(relaxed = true)
    jwtService = JwtService(secretConfig)
    sepBeans = SepBeans()
  }

  @Test
  fun `test info, price, prices were excluded in filter when auth not required`() {
    val sep10TokenFilter = sepBeans.sep10TokenFilter(jwtService, sep38Config)
    assert(!sep10TokenFilter.urlPatterns.contains("/sep38/info"))
    assert(!sep10TokenFilter.urlPatterns.contains("/sep38/price"))
    assert(!sep10TokenFilter.urlPatterns.contains("/sep38/prices"))
  }

  @Test
  fun `test info, price, prices endpoints were included in filter when auth required`() {
    every { sep38Config.isAuthEnforced } returns true
    val sep10TokenFilter = sepBeans.sep10TokenFilter(jwtService, sep38Config)
    assert(sep10TokenFilter.urlPatterns.contains("/sep38/info"))
    assert(sep10TokenFilter.urlPatterns.contains("/sep38/price"))
    assert(sep10TokenFilter.urlPatterns.contains("/sep38/prices"))
  }
}
