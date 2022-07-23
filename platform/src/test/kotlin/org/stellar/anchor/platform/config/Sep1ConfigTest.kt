package org.stellar.anchor.platform.config

import org.stellar.anchor.config.Sep31Config.DepositInfoGeneratorType.CIRCLE

import org.junit.jupiter.api.*
import org.springframework.validation.BindException
import org.springframework.validation.ValidationUtils
import kotlin.test.assertContains
import kotlin.test.assertEquals

open class Sep1ConfigTest {
    @Test
    fun testSep1ConfigValid() {
        val sep1Config = PropertySep1Config()
        sep1Config.stellarFile = "classpath:/sep1/stellar-wks.toml"

        val errors = BindException(sep1Config, "sep1Config")
        ValidationUtils.invokeValidator(sep1Config, sep1Config, errors)
        assertEquals(0, errors.errorCount)
    }

    @Test
    fun testSep1ConfigBadFile() {
        val sep1Config = PropertySep1Config()
        sep1Config.stellarFile = "classpath:/sep1/stellar-dne.toml"

        val errors = BindException(sep1Config, "sep1Config")
        ValidationUtils.invokeValidator(sep1Config, sep1Config, errors)
        assertEquals(1, errors.errorCount)
        errors.message?.let { assertContains(it, "doesNotExist-stellarFile") }
    }
}