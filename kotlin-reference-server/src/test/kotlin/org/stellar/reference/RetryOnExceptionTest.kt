package org.stellar.reference

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RetryOnExceptionTest {
  @Test
  fun `retries until the block succeeds`() = runBlocking {
    var calls = 0
    retryOnException(maxAttempts = 5, delaySeconds = 0) {
      calls++
      if (calls < 3) throw RuntimeException("transient")
    }
    assertEquals(3, calls)
  }

  @Test
  fun `rethrows after exhausting the attempts`() {
    var calls = 0
    assertThrows(IllegalStateException::class.java) {
      runBlocking {
        retryOnException(maxAttempts = 2, delaySeconds = 0) {
          calls++
          throw IllegalStateException("always fails")
        }
      }
    }
    assertEquals(3, calls)
  }
}
