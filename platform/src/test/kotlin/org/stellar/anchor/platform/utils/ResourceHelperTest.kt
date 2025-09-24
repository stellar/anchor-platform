package org.stellar.anchor.platform.utils

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ResourceHelperTest {
  @ParameterizedTest
  @ValueSource(
    strings = ["config/anchor-config-default-values.yaml", "config/anchor-config-schema-v1.yaml"]
  )
  fun `test reading resource file`(resource: String) {
    val resource = ResourceHelper.resource(resource)
    assertTrue(resource.exists())
    assertTrue(resource.isFile)
  }
}
