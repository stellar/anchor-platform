@file:JvmName("RunDockerDevStackNoWait")

package org.stellar.anchor.platform.run_profiles

import kotlinx.coroutines.runBlocking
import org.stellar.anchor.platform.*

fun main() = runBlocking {
  testProfileExecutor = TestProfileExecutor(TestConfig())
  // The "registerShutdownHook(testProfileExecutor)" is commented out to avoid shutting down the
  // docker compose stack when the JVM is shutdown.
  testProfileExecutor.start(true) {
    it.env[RUN_DOCKER] = "true"
    it.env[RUN_ALL_SERVERS] = "false"
  }
}
