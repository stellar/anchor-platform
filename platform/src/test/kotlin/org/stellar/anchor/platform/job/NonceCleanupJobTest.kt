package org.stellar.anchor.platform.job

import io.mockk.MockKAnnotations
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import kotlin.test.Test
import org.junit.jupiter.api.BeforeEach
import org.stellar.anchor.auth.NonceStore

class NonceCleanupJobTest {

  @MockK(relaxed = true) private lateinit var nonceStore: NonceStore
  private lateinit var nonceCleanupJob: NonceCleanupJob

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    nonceCleanupJob = NonceCleanupJob(nonceStore)
  }

  @Test
  fun test_cleanup() {
    nonceCleanupJob.cleanup()
    verify(exactly = 1) { nonceStore.deleteExpiredNonces() }
  }
}
