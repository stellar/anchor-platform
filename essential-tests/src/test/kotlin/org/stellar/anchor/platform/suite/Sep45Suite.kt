package org.stellar.anchor.platform.suite

import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import org.stellar.anchor.platform.integrationtest.Sep45Tests

@Suite @SelectClasses(Sep45Tests::class) class Sep45Suite
