package org.stellar.anchor.platform.configurator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DataConfigAdapterTest {

  // ANCHOR-1587 (HackerOne #4241206): with an entity manager bound to the whole request, the commit
  // of one JSON-RPC batch item writes the pending changes of a rejected one.
  @Test
  fun `the Spring environment turns open-in-view off`() {
    val adapter = DataConfigAdapter()

    adapter.updateSpringEnv(ConfigHelper.loadDefaultConfig())

    assertEquals(false, adapter.props["spring.jpa.open-in-view"])
  }
}
