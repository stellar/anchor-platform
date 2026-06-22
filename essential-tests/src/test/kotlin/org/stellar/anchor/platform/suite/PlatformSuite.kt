package org.stellar.anchor.platform.suite

import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import org.stellar.anchor.platform.integrationtest.CallbackApiTests
import org.stellar.anchor.platform.integrationtest.CallbackSignatureTest
import org.stellar.anchor.platform.integrationtest.EventProcessingServerTests
import org.stellar.anchor.platform.integrationtest.MultiClientCallbackTests
import org.stellar.anchor.platform.integrationtest.PaymentObserverTests
import org.stellar.anchor.platform.integrationtest.RpcPlatformApiTests
import org.stellar.anchor.platform.integrationtest.StellarObserverTests

@Suite
@SelectClasses(
  CallbackApiTests::class,
  CallbackSignatureTest::class,
  EventProcessingServerTests::class,
  MultiClientCallbackTests::class,
  RpcPlatformApiTests::class,
  StellarObserverTests::class,
  PaymentObserverTests::class,
)
class PlatformSuite
