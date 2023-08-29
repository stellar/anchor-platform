package org.stellar.anchor.platform

import org.junit.jupiter.api.*

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AnchorPlatformEnd2EndTest : AbstractIntegrationTest(TestConfig(testProfileName = "default")) {

  companion object {
    private val singleton = AnchorPlatformEnd2EndTest()

    @BeforeAll
    @JvmStatic
    fun construct() {
      println("Running AnchorPlatformEnd2EndTest")
      singleton.setUp()
    }

    @AfterAll
    @JvmStatic
    fun destroy() {
      singleton.tearDown()
    }
  }

  @Test
  @Order(1)
  fun runSep24Test() {
    singleton.sep24E2eTests.testAll()
  }
}
