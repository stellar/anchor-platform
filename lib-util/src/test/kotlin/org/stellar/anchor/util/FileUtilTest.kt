package org.stellar.anchor.util

import java.io.IOException
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class FileUtilTest {
  @Test
  fun `test getRourceFileAsString() returns correct value`() {
    val value = FileUtil.getResourceFileAsString("test_resource.txt")
    assert(value.equals("test_resource.txt"))
  }

  @Test
  fun `test read with max size`() {
    val path = Files.createTempFile("file-util", ".txt")
    Files.writeString(path, "test-content")

    assertEquals("test-content", FileUtil.read(path, 1024))
  }

  @Test
  fun `test read with max size throws on oversized file`() {
    val path = Files.createTempFile("file-util", ".txt")
    Files.writeString(path, "test-content")

    assertThrows<IOException> { FileUtil.read(path, 4) }
  }
}
