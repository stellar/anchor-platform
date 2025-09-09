package org.stellar.anchor.platform.validator

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import jakarta.validation.ConstraintViolation
import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.api.exception.rpc.InvalidParamsException

class SepRequestValidatorTest {

  @MockK(relaxed = true) private lateinit var validator: Validator

  private lateinit var requestValidator: RequestValidator

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    this.requestValidator = RequestValidator(validator)
  }

  @Test
  fun test_validate_invalidRequest() {
    val request = "testRequest"
    val violation1: ConstraintViolation<String> = mockk()
    val violation2: ConstraintViolation<String> = mockk()

    every { violation1.message } returns "violation error message 1"
    every { violation2.message } returns "violation error message 2"
    every { validator.validate(request) } returns setOf(violation1, violation2)

    val ex = assertThrows<InvalidParamsException> { requestValidator.validate(request) }
    assertEquals(
      """
  violation error message 1
  violation error message 2
  """
        .trimIndent(),
      ex.message?.trimIndent(),
    )
  }

  @Test
  fun test_validate_validRequest() {
    val request = "testRequest"

    every { validator.validate(request) } returns setOf()

    requestValidator.validate(request)
  }
}
