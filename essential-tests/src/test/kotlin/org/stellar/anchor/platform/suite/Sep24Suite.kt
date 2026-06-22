package org.stellar.anchor.platform.suite

import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import org.stellar.anchor.platform.e2etest.Sep24End2EndTests
import org.stellar.anchor.platform.integrationtest.Sep24PlatformApiTests
import org.stellar.anchor.platform.integrationtest.Sep24Tests

@Suite
@SelectClasses(Sep24Tests::class, Sep24PlatformApiTests::class, Sep24End2EndTests::class)
class Sep24Suite
