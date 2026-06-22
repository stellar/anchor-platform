package org.stellar.anchor.platform.suite

import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import org.stellar.anchor.platform.integrationtest.KafkaTests
import org.stellar.anchor.platform.integrationtest.LedgerClientTests
import org.stellar.anchor.platform.integrationtest.PlatformServerHealthTests

@Suite
@SelectClasses(PlatformServerHealthTests::class, KafkaTests::class, LedgerClientTests::class)
class InfrastructureSuite
