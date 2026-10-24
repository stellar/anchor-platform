package org.stellar.reference

import io.github.oshai.kotlinlogging.KLogger
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.reference.event.EventConsumer

class RunEventConsumerTest {
  private val consumer: EventConsumer = mockk()
  private val logger: KLogger = mockk(relaxed = true)

  @Test
  fun `a consumer that throws is logged at ERROR and the throwable is rethrown`() {
    val failure = OutOfMemoryError("simulated")
    coEvery { consumer.start() } throws failure
    val messages = mutableListOf<() -> Any?>()
    every { logger.error(failure, capture(messages)) } returns Unit

    val thrown = assertThrows<OutOfMemoryError> { runEventConsumer(consumer, logger) }

    assertEquals(failure, thrown)
    assertEquals(1, messages.size)
    assertEquals("Event consumer stopped unexpectedly", messages.single()())
  }

  @Test
  fun `a consumer that returns normally logs INFO and no ERROR`() {
    coEvery { consumer.start() } returns consumer

    runEventConsumer(consumer, logger)

    verify(exactly = 0) { logger.error(any<Throwable>(), any()) }
    verify(exactly = 0) { logger.error(any<() -> Any?>()) }
    verify(atLeast = 1) { logger.info(any<() -> Any?>()) }
  }
}
