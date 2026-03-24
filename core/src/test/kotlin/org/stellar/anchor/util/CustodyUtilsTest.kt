package org.stellar.anchor.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.config.CustodyConfig.CustodyType.NONE

class CustodyUtilsTest {

  @ParameterizedTest
  @ValueSource(strings = ["id", "text", "hash", "none", "return"])
  fun test_isMemoTypeSupported_noneCustody_supported(memoType: String) {
    assertTrue(CustodyUtils.isMemoTypeSupported(NONE, memoType))
  }

  @ParameterizedTest
  @ValueSource(strings = ["test"])
  fun test_isMemoTypeSupported_noneCustody_notSupported(memoType: String) {
    assertTrue(CustodyUtils.isMemoTypeSupported(NONE, memoType))
  }

  @ParameterizedTest
  @ValueSource(strings = ["id", "text", "none", "return"])
  fun test_isMemoTypeSupported_fireblocksCustody_supported(memoType: String) {
    assertTrue(CustodyUtils.isMemoTypeSupported(NONE, memoType))
  }

  @ParameterizedTest
  @ValueSource(strings = ["hash", "test"])
  fun test_isMemoTypeSupported_fireblocksCustody_notSupported(memoType: String) {
    assertTrue(CustodyUtils.isMemoTypeSupported(NONE, memoType))
  }
}
