package org.stellar.anchor.platform.suite

import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import org.stellar.anchor.platform.integrationtest.Sep38PlatformApiTests
import org.stellar.anchor.platform.integrationtest.Sep38Tests

@Suite @SelectClasses(Sep38Tests::class, Sep38PlatformApiTests::class) class Sep38Suite
