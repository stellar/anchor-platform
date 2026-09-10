package org.stellar.anchor.platform.component

import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.util.function.Supplier
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.stellar.anchor.MoreInfoUrlConstructor
import org.stellar.anchor.api.callback.CustomerIntegration
import org.stellar.anchor.api.callback.RateIntegration
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.auth.NonceManager
import org.stellar.anchor.auth.NonceStore
import org.stellar.anchor.client.ClientFinder
import org.stellar.anchor.client.ClientService
import org.stellar.anchor.config.LanguageConfig
import org.stellar.anchor.config.SecretConfig
import org.stellar.anchor.config.Sep6Config
import org.stellar.anchor.config.StellarNetworkConfig
import org.stellar.anchor.event.EventService
import org.stellar.anchor.ledger.LedgerClient
import org.stellar.anchor.platform.component.sep.SepBeans
import org.stellar.anchor.platform.config.CallbackApiConfig
import org.stellar.anchor.platform.config.PropertySep24Config
import org.stellar.anchor.platform.config.PropertySep31Config
import org.stellar.anchor.sep10.Sep10Service
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31CustomerIdOwnerStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.sep38.Sep38QuoteStore
import org.stellar.anchor.sep6.Sep6TransactionStore
import org.stellar.anchor.util.ExchangeAmountsCalculator
import org.stellar.anchor.util.SepRequestValidator

class SepBeansConditionalRegistrationTest {

  companion object {
    @JvmStatic
    fun singleSepEnabledScenarios() =
      listOf(
        arrayOf("sep6.enabled", "true", "sep24.enabled", "false", "sep31.enabled", "false"),
        arrayOf("sep6.enabled", "false", "sep24.enabled", "true", "sep31.enabled", "false"),
        arrayOf("sep6.enabled", "false", "sep24.enabled", "false", "sep31.enabled", "true"),
      )
  }

  private fun baseRunner(): ApplicationContextRunner =
    ApplicationContextRunner()
      .withConfiguration(
        AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration::class.java)
      )
      .withBean(
        StellarNetworkConfig::class.java,
        Supplier { mockk<StellarNetworkConfig>(relaxed = true) },
      )
      .withBean(
        SecretConfig::class.java,
        Supplier {
          mockk<SecretConfig>(relaxed = true) {
            // Valid enough to satisfy PropertySep10Config/PropertySep45Config's validation when
            // sep10/sep45 are enabled; unused (and irrelevant) when they're disabled.
            every { sep10SigningSeed } returns
              "SBVEOFAHGJCKGR4AAM7RTDRCP6RMYYV5YUV32ZK7ZD3VPDGGHYLXTZRZ"
            every { sep10JwtSecretKey } returns "sep10_jwt_secret_key_for_tests_only_______"
          }
        },
      )
      .withBean(ClientService::class.java, Supplier { mockk<ClientService>(relaxed = true) })
      .withBean(
        CallbackApiConfig::class.java,
        Supplier { mockk<CallbackApiConfig>(relaxed = true) },
      )
      .withBean(JwtService::class.java, Supplier { mockk<JwtService>(relaxed = true) })
      .withBean(AssetService::class.java, Supplier { mockk<AssetService>(relaxed = true) })
      .withBean(
        CustomerIntegration::class.java,
        Supplier { mockk<CustomerIntegration>(relaxed = true) },
      )
      .withBean(
        PropertySep24Config::class.java,
        Supplier { mockk<PropertySep24Config>(relaxed = true) },
      )
      .withBean(LanguageConfig::class.java, Supplier { mockk<LanguageConfig>(relaxed = true) })
      .withBean(Sep38QuoteStore::class.java, Supplier { mockk<Sep38QuoteStore>(relaxed = true) })
      .withBean(EventService::class.java, Supplier { mockk<EventService>(relaxed = true) })
      .withBean(Sep6Config::class.java, Supplier { mockk<Sep6Config>(relaxed = true) })
      .withBean(
        Sep6TransactionStore::class.java,
        Supplier { mockk<Sep6TransactionStore>(relaxed = true) },
      )
      .withBean(
        "sep6MoreInfoUrlConstructor",
        MoreInfoUrlConstructor::class.java,
        Supplier { mockk<MoreInfoUrlConstructor>(relaxed = true) },
      )
      .withBean(
        Sep24TransactionStore::class.java,
        Supplier { mockk<Sep24TransactionStore>(relaxed = true) },
      )
      .withBean(
        "sep24MoreInfoUrlConstructor",
        MoreInfoUrlConstructor::class.java,
        Supplier { mockk<MoreInfoUrlConstructor>(relaxed = true) },
      )
      .withBean(
        PropertySep31Config::class.java,
        Supplier { mockk<PropertySep31Config>(relaxed = true) },
      )
      .withBean(
        Sep31TransactionStore::class.java,
        Supplier { mockk<Sep31TransactionStore>(relaxed = true) },
      )
      .withBean(RateIntegration::class.java, Supplier { mockk<RateIntegration>(relaxed = true) })
      .withBean(
        Sep31CustomerIdOwnerStore::class.java,
        Supplier { mockk<Sep31CustomerIdOwnerStore>(relaxed = true) },
      )
      .withBean(Clock::class.java, Supplier { Clock.systemUTC() })
      .withPropertyValues(
        "sep10.enabled=false",
        "sep10.home_domains=example.com",
        "sep45.enabled=false",
        "sep45.home_domains=example.com",
      )
      .withUserConfiguration(SepBeans::class.java)

  @ParameterizedTest
  @MethodSource("singleSepEnabledScenarios")
  fun `context exposes exactly one ExchangeAmountsCalculator when exactly one of sep6, sep24, sep31 is enabled`(
    sep6Key: String,
    sep6Value: String,
    sep24Key: String,
    sep24Value: String,
    sep31Key: String,
    sep31Value: String,
  ) {
    baseRunner()
      .withPropertyValues("$sep6Key=$sep6Value", "$sep24Key=$sep24Value", "$sep31Key=$sep31Value")
      .run { context ->
        assert(context.startupFailure == null) {
          "context failed to start with $sep6Key=$sep6Value, $sep24Key=$sep24Value, " +
            "$sep31Key=$sep31Value: ${context.startupFailure}"
        }
        assert(context.getBeanNamesForType(ExchangeAmountsCalculator::class.java).size == 1) {
          "expected exactly one ExchangeAmountsCalculator bean with $sep6Key=$sep6Value, " +
            "$sep24Key=$sep24Value, $sep31Key=$sep31Value"
        }
      }
  }

  @Test
  fun `context exposes no ExchangeAmountsCalculator and no ClientFinder or SepRequestValidator when none of sep6, sep24, sep31 are enabled`() {
    baseRunner()
      .withPropertyValues("sep6.enabled=false", "sep24.enabled=false", "sep31.enabled=false")
      .run { context ->
        assert(context.startupFailure == null) {
          "context failed to start with sep6/sep24/sep31 all disabled: ${context.startupFailure}"
        }
        assert(context.getBeanNamesForType(ExchangeAmountsCalculator::class.java).isEmpty()) {
          "expected no ExchangeAmountsCalculator bean when sep6/sep24/sep31 are all disabled"
        }
        assert(context.getBeanNamesForType(ClientFinder::class.java).isEmpty()) {
          "expected no ClientFinder bean when sep6/sep10/sep24/sep31 are all disabled"
        }
        assert(context.getBeanNamesForType(SepRequestValidator::class.java).isEmpty()) {
          "expected no SepRequestValidator bean when sep6/sep24 are both disabled"
        }
      }
  }

  @Test
  fun `context exposes NonceManager and Sep10Service when only sep10 is enabled`() {
    baseRunner()
      .withBean(LedgerClient::class.java, Supplier { mockk<LedgerClient>(relaxed = true) })
      .withBean(NonceStore::class.java, Supplier { mockk<NonceStore>(relaxed = true) })
      .withPropertyValues(
        "sep6.enabled=false",
        "sep24.enabled=false",
        "sep31.enabled=false",
        "sep10.enabled=true",
      )
      .run { context ->
        assert(context.startupFailure == null) {
          "context failed to start with only sep10 enabled: ${context.startupFailure}"
        }
        assert(context.getBeanNamesForType(NonceManager::class.java).size == 1) {
          "expected exactly one NonceManager bean when only sep10 is enabled"
        }
        assert(context.getBeanNamesForType(Sep10Service::class.java).size == 1) {
          "expected exactly one Sep10Service bean when only sep10 is enabled"
        }
      }
  }
}
