@file:JvmName("RunPlatformServer")

package org.stellar.anchor.platform.run_profiles

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.stellar.anchor.platform.*

fun main() = runBlocking {
  testProfileExecutor = TestProfileExecutor(TestConfig())
  launch { registerShutdownHook(testProfileExecutor) }
  testProfileExecutor.start(true) {
    it.env[RUN_DOCKER] = "false"
    it.env[RUN_ALL_SERVERS] = "false"
    it.env[RUN_PLATFORM_SERVER] = "true"
  }
}
