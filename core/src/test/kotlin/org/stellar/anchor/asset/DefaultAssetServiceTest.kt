package org.stellar.anchor.asset

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import org.apache.commons.io.FilenameUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode.LENIENT
import org.stellar.anchor.api.exception.InvalidConfigException
import org.stellar.anchor.api.exception.SepNotFoundException
import org.stellar.anchor.util.GsonUtils

internal class DefaultAssetServiceTest {
  private val gson: Gson = GsonUtils.getInstance()

  @BeforeEach fun setup() {}

  @ParameterizedTest
  @ValueSource(strings = ["test_assets.json", "test_assets.yaml"])
  fun `test assets loading and listing`(filename: String) {

    lateinit var das: DefaultAssetService
    when (FilenameUtils.getExtension(filename)) {
      "json" -> das = DefaultAssetService.fromJsonResource(filename)
      "yaml" -> das = DefaultAssetService.fromYamlResource(filename)
    }

    val assets = das.getAssets()
    assertEquals(4, assets.size)
    JSONAssert.assertEquals(expectedAssetsJson, gson.toJson(assets), LENIENT)
  }

  @Test
  fun `test asset JSON file not found`() {
    assertThrows<SepNotFoundException> { DefaultAssetService.fromJsonResource("not_found.json") }

    assertThrows<SepNotFoundException> {
      DefaultAssetService.fromJsonResource("classpath:/test_assets.json")
    }
  }

  @Test
  fun `test bad JSON and YAML file format`() {
    assertThrows<JsonSyntaxException> {
      DefaultAssetService.fromJsonResource("test_assets.json.bad")
    }
  }

  @Test
  fun `test invalid config with duplicate assets`() {
    assertThrows<InvalidConfigException> {
      DefaultAssetService.fromYamlResource("test_assets_duplicate_asset.yaml")
    }
  }

  @Test
  fun `test invalid config with duplicate withdraw type when sep-6 enabled`() {
    assertThrows<InvalidConfigException> {
      DefaultAssetService.fromYamlResource("test_assets_duplicate_withdraw_type.yaml")
    }
  }

  @Test
  fun `test invalid config with missing withdraw type when sep-6 enabled`() {
    assertThrows<InvalidConfigException> {
      DefaultAssetService.fromYamlResource("test_assets_missing_withdraw_type.yaml")
    }
  }

  @Test
  fun `test invalid config with duplicate deposit type when sep-6 enabled`() {
    assertThrows<InvalidConfigException> {
      DefaultAssetService.fromYamlResource("test_assets_duplicate_deposit_type.yaml")
    }
  }

  @Test
  fun `test invalid config with missing deposit type when sep-6 enabled`() {
    assertThrows<InvalidConfigException> {
      DefaultAssetService.fromYamlResource("test_assets_missing_deposit_type.yaml")
    }
  }

  @Test
  fun `test invalid config with duplicate receive type when sep-31 enabled`() {
    assertThrows<InvalidConfigException> {
      DefaultAssetService.fromYamlResource("test_assets_duplicate_receive_methods.yaml")
    }
  }

  @Test
  fun `test invalid config with missing receive type when sep-31 enabled`() {
    assertThrows<InvalidConfigException> {
      DefaultAssetService.fromYamlResource("test_assets_missing_receive_method.yaml")
    }
  }

  @Test
  fun `test trailing comma in JSON does not result in null element`() {
    val assetsService = DefaultAssetService.fromJsonContent(trailingCommaInAssets)
    assert(assetsService.stellarAssets.all { it != null })
  }

  // This is supposed to match the result from loading test_assets.json file.
  private val expectedAssetsJson =
    """
       [
              {
                "id": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
                "distribution_account": "GA7FYRB5VREZKOBIIKHG5AVTPFGWUBPOBF7LTYG4GTMFVIOOD2DWAL7I",
                "significant_decimals": 2,
                "sep6": {
                  "enabled": true,
                  "deposit": {
                    "enabled": true,
                    "min_amount": 1,
                    "max_amount": 10000,
                    "methods": [
                      "SEPA",
                      "SWIFT"
                    ]
                  },
                  "withdraw": {
                    "enabled": true,
                    "min_amount": 1,
                    "max_amount": 10000,
                    "methods": [
                      "bank_account",
                      "cash"
                    ]
                  }
                },
                "sep24": {
                  "enabled": true,
                  "deposit": {
                    "enabled": true,
                    "min_amount": 1,
                    "max_amount": 10000,
                    "methods": [
                      "SEPA",
                      "SWIFT"
                    ]
                  },
                  "withdraw": {
                    "enabled": true,
                    "min_amount": 1,
                    "max_amount": 10000,
                    "methods": [
                      "bank_account",
                      "cash"
                    ]
                  }
                },
                "sep31": {
                  "enabled": true,
                  "receive": {
                    "min_amount": 1,
                    "max_amount": 1000000,
                    "methods": [
                      "SEPA",
                      "SWIFT"
                    ]
                  },
                  "quotes_supported": true,
                  "quotes_required": true
                },
                "sep38": {
                  "enabled": true,
                  "exchangeable_assets": [
                    "stellar:JPYC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5",
                    "iso4217:USD"
                  ]
                }
              },
              {
                "id": "stellar:JPYC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5",
                "significant_decimals": 2,
                "sep6" : {
                  "enabled": false
                },
                "sep24": {
                  "enabled": true,
                  "deposit": {
                    "enabled": true,
                    "min_amount": 1,
                    "max_amount": 1000000
                  },
                  "withdraw": {
                    "enabled": false,
                    "min_amount": 1,
                    "max_amount": 1000000
                  }
                },
          
                "sep31": {
                  "enabled": true,
                  "receive": {
                    "min_amount": 1,
                    "max_amount": 1000000,
                    "methods": [
                      "SEPA",
                      "SWIFT"
                    ]
                  },
                  "quotes_supported": true,
                  "quotes_required": true
                },
                "sep38": {
                  "enabled": true,
                  "exchangeable_assets": [
                    "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
                    "iso4217:USD"
                  ]
                }
              },
              {
                "id": "iso4217:USD",
                "significant_decimals": 2,
                "sep31": {
                  "enabled": false,
                  "receive": {
                    "min_amount": 1,
                    "max_amount": 1000000,
                    "methods": [
                      "SEPA",
                      "SWIFT"
                    ]
                  }
                },
                "sep38": {
                  "enabled": true,
                  "exchangeable_assets": [
                    "stellar:JPYC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5",
                    "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
                  ],
                  "country_codes": [
                    "US"
                  ],
                  "sell_delivery_methods": [
                    {
                      "name": "WIRE",
                      "description": "Send USD directly to the Anchor's bank account."
                    }
                  ],
                  "buy_delivery_methods": [
                    {
                      "name": "WIRE",
                      "description": "Have USD sent directly to your bank account."
                    }
                  ]
                }
              },
              {
                "id": "stellar:native",
                "significant_decimals": 7,
                "sep6": {
                  "enabled": false
                },
                "sep24": {
                  "enabled": true,
                  "deposit": {
                    "enabled": true,
                    "min_amount": 1,
                    "max_amount": 1000000
                  },
                  "withdraw": {
                    "enabled": true,
                    "min_amount": 1,
                    "max_amount": 1000000
                  }
                },
                "sep31": {
                  "enabled": true,
                  "receive": {
                    "min_amount": 1,
                    "max_amount": 1000000
                  },
                  "quotes_supported": true,
                  "quotes_required": true
                },
                "sep38": {
                  "enabled": true,
                  "exchangeable_assets": [
                    "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
                  ]
                }
              }
            ]
          
    """
      .trimIndent()
}

val trailingCommaInAssets =
  """
  {
    "items": [
      {
        "id": "stellar:native",
        "distribution_account": "GBN4NNCDGJO4XW4KQU3CBIESUJWFVBUZPOKUZHT7W7WRB7CWOA7BXVQF",
        "significant_decimals": 7
      },
    ]
  }
"""
    .trimIndent()
