package org.stellar.anchor.platform.suite

import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import org.stellar.anchor.platform.integrationtest.Sep10ServiceIntegrationTests
import org.stellar.anchor.platform.integrationtest.Sep10Tests

@Suite @SelectClasses(Sep10Tests::class, Sep10ServiceIntegrationTests::class) class Sep10Suite
