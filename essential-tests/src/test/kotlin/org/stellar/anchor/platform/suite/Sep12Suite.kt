package org.stellar.anchor.platform.suite

import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import org.stellar.anchor.platform.integrationtest.Sep12Tests

@Suite @SelectClasses(Sep12Tests::class) class Sep12Suite
