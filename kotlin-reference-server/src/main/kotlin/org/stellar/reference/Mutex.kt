package org.stellar.reference

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// This is to make sure transaction submission is mutual exclusive to avoid failures
internal val submissionLock = Mutex()

suspend fun retryOnException(
  maxAttempts: Int = 5,
  delaySeconds: Int = 5,
  block: suspend () -> Unit,
) =
  flow<Unit> { block() }
    .retryWhen { cause, attempt ->
      if (cause is CancellationException) {
        false
      } else if (attempt < maxAttempts) {
        delay(delaySeconds.seconds)
        true
      } else {
        false
      }
    }
    .collect {}

suspend fun transactionWithRetry(
  maxAttempts: Int = 5,
  delay: Int = 5,
  transactionLogic: suspend () -> Unit
) =
  flow<Unit> { submissionLock.withLock { transactionLogic() } }
    .retryWhen { _, attempt ->
      if (attempt < maxAttempts) {
        delay((delay + (1..5).random()).seconds)
        return@retryWhen true
      } else {
        return@retryWhen false
      }
    }
    .collect {}
