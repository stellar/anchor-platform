package org.stellar.anchor.platform

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.*
import org.stellar.anchor.platform.test.*

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AnchorPlatformIntegrationTest : AbstractIntegrationTest(TestConfig(profileName = "default")) {
  companion object {
    private val singleton = AnchorPlatformIntegrationTest()
    private val custodyMockServer = MockWebServer()

    @BeforeAll
    @JvmStatic
    fun construct() {
      custodyMockServer.start()
      singleton.setUp(mapOf("custody.fireblocks.base_url" to custodyMockServer.url("").toString()))
    }

    @AfterAll
    @JvmStatic
    fun destroy() {
      custodyMockServer.shutdown()
      singleton.tearDown()
    }
  }

  @Test
  @Order(1)
  fun runSep10Test() {
    singleton.sep10Tests.testAll()
  }

  @Test
  @Order(2)
  fun runSep12Test() {
    singleton.sep12Tests.testAll()
  }

  @Test
  @Order(3)
  fun runSep24Test() {
    singleton.sep24Tests.testAll()
  }

  @Test
  @Order(4)
  fun runSep31Test() {
    singleton.sep31Tests.testAll()
  }

  @Test
  @Order(5)
  fun runSep38Test() {
    singleton.sep38Tests.testAll()
  }

  @Test
  @Order(6)
  fun runPlatformApiTest() {
    singleton.platformApiTests.testAll()
  }

  @Test
  @Order(7)
  fun runCallbackApiTest() {
    singleton.callbackApiTests.testAll()
  }

  @Test
  @Order(8)
  fun runStellarObserverTest() {
    singleton.stellarObserverTests.testAll()
  }

  @Test
  @Order(9)
  fun runCustodyApiTest() {
    singleton.custodyApiTests.testAll(custodyMockServer)
  }
}
