package org.stellar.anchor.platform.suite

import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import org.stellar.anchor.platform.e2etest.Sep6End2EndTest
import org.stellar.anchor.platform.integrationtest.Sep6PlatformApiTests
import org.stellar.anchor.platform.integrationtest.Sep6Tests

@Suite
@SelectClasses(Sep6Tests::class, Sep6PlatformApiTests::class, Sep6End2EndTest::class)
class Sep6Suite
