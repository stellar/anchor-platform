package org.stellar.anchor.platform.component.share

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.env.Environment

class ObservingAccountsBeansTest {
  private fun envWith(sep6: Boolean, sep24: Boolean, sep31: Boolean): Environment {
    val env = mockk<Environment>()
    every { env.getProperty("sep6.enabled", Boolean::class.javaObjectType, false) } returns sep6
    every { env.getProperty("sep24.enabled", Boolean::class.javaObjectType, false) } returns sep24
    every { env.getProperty("sep31.enabled", Boolean::class.javaObjectType, false) } returns sep31
    return env
  }

  @Test
  fun `test scheduler is not started when no protocol using transient observation is enabled`() {
    assertFalse(ObservingAccountsBeans.shouldStartEvictionScheduler(envWith(false, false, false)))
  }

  @Test
  fun `test scheduler is started when only sep6 is enabled`() {
    assertTrue(ObservingAccountsBeans.shouldStartEvictionScheduler(envWith(true, false, false)))
  }

  @Test
  fun `test scheduler is started when only sep24 is enabled`() {
    assertTrue(ObservingAccountsBeans.shouldStartEvictionScheduler(envWith(false, true, false)))
  }

  @Test
  fun `test scheduler is started when only sep31 is enabled`() {
    assertTrue(ObservingAccountsBeans.shouldStartEvictionScheduler(envWith(false, false, true)))
  }
}
