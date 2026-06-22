package org.stellar.anchor.platform.suite

import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import org.stellar.anchor.platform.e2etest.Sep31End2EndTests
import org.stellar.anchor.platform.integrationtest.Sep31PlatformApiTests
import org.stellar.anchor.platform.integrationtest.Sep31Tests

@Suite
@SelectClasses(Sep31Tests::class, Sep31PlatformApiTests::class, Sep31End2EndTests::class)
class Sep31Suite
