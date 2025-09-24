package org.stellar.anchor.platform.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.validation.Errors

fun assertErrorCode(errors: Errors, code: String) {
  assertErrorCode(errors, 0, code)
}

fun assertErrorCode(errors: Errors, index: Int, code: String) {
  assertTrue(errors.hasErrors())
  assertEquals(code, errors.allErrors[index].code)
}
