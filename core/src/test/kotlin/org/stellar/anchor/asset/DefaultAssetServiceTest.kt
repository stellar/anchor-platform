package org.stellar.anchor.asset

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode.LENIENT
import org.stellar.anchor.api.exception.SepNotFoundException
import org.stellar.anchor.util.GsonUtils
import org.yaml.snakeyaml.scanner.ScannerException
import shadow.org.apache.commons.io.FilenameUtils

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

    JSONAssert.assertEquals(expectedAssetsJson, gson.toJson(das.assets), LENIENT)

    // check listing function.
    val assets = das.listAllAssets()

    assertEquals(4, assets.size)
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

    assertThrows<ScannerException> { DefaultAssetService.fromYamlResource("test_assets.yaml.bad") }
  }

  // This is supposed to match the result from loading test_assets.json file.
  private val expectedAssetsJson =
    """
      {
        "assets": [
          {
            "code": "USDC",
            "issuer": "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
            "distribution_account": "GA7FYRB5VREZKOBIIKHG5AVTPFGWUBPOBF7LTYG4GTMFVIOOD2DWAL7I",
            "schema": "stellar",
            "significant_decimals": 2,
            "deposit": {
              "enabled": true,
              "min_amount": 1,
              "max_amount": 10000
            },
            "withdraw": {
              "enabled": true,
              "min_amount": 1,
              "max_amount": 10000
            },
            "send": {
              "fee_fixed": 0,
              "fee_percent": 0,
              "min_amount": 1,
              "max_amount": 1000000
            },
            "sep31": {
              "quotes_supported": true,
              "quotes_required": true,
              "sep12": {
                "sender": {
                  "types": {
                    "sep31-sender": {
                      "description": "U.S. citizens limited to sending payments of less than ${'$'}10,000 in value"
                    },
                    "sep31-large-sender": {
                      "description": "U.S. citizens that do not have sending limits"
                    },
                    "sep31-foreign-sender": {
                      "description": "non-U.S. citizens sending payments of less than ${'$'}10,000 in value"
                    }
                  }
                },
                "receiver": {
                  "types": {
                    "sep31-receiver": {
                      "description": "U.S. citizens receiving USD"
                    },
                    "sep31-foreign-receiver": {
                      "description": "non-U.S. citizens receiving USD"
                    }
                  }
                }
              },
              "fields": {
                "transaction": {
                  "receiver_routing_number": {
                    "description": "routing number of the destination bank account",
                    "optional": false
                  },
                  "receiver_account_number": {
                    "description": "bank account number of the destination",
                    "optional": false
                  },
                  "type": {
                    "description": "type of deposit to make",
                    "choices": [
                      "SEPA",
                      "SWIFT"
                    ],
                    "optional": false
                  }
                }
              }
            },
            "sep38": {
              "exchangeable_assets": [
                "stellar:JPYC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5",
                "iso4217:USD"
              ]
            },
            "sep24_enabled": true,
            "sep31_enabled": true,
            "sep38_enabled": true
          },
          {
            "code": "JPYC",
            "issuer": "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5",
            "schema": "stellar",
            "significant_decimals": 2,
            "deposit": {
              "enabled": true,
              "min_amount": 1,
              "max_amount": 1000000
            },
            "withdraw": {
              "enabled": false,
              "min_amount": 1,
              "max_amount": 1000000
            },
            "send": {
              "fee_fixed": 0,
              "fee_percent": 0,
              "min_amount": 1,
              "max_amount": 1000000
            },
            "sep31": {
              "quotes_supported": true,
              "quotes_required": true,
              "sep12": {
                "sender": {
                  "types": {
                    "sep31-sender": {
                      "description": "Japanese citizens"
                    }
                  }
                },
                "receiver": {
                  "types": {
                    "sep31-receiver": {
                      "description": "Japanese citizens receiving USD"
                    }
                  }
                }
              },
              "fields": {
                "transaction": {
                  "receiver_routing_number": {
                    "description": "routing number of the destination bank account",
                    "optional": false
                  },
                  "receiver_account_number": {
                    "description": "bank account number of the destination",
                    "optional": false
                  },
                  "type": {
                    "description": "type of deposit to make",
                    "choices": [
                      "ACH",
                      "SWIFT",
                      "WIRE"
                    ],
                    "optional": false
                  }
                }
              }
            },
            "sep38": {
              "exchangeable_assets": [
                "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
                "iso4217:USD"
              ]
            },
            "sep24_enabled": true,
            "sep31_enabled": true,
            "sep38_enabled": true
          },
          {
            "code": "USD",
            "schema": "iso4217",
            "significant_decimals": 2,
            "deposit": {
              "enabled": true,
              "min_amount": 1,
              "max_amount": 1000000
            },
            "withdraw": {
              "enabled": false,
              "min_amount": 1,
              "max_amount": 1000000
            },
            "send": {
              "fee_fixed": 0,
              "fee_percent": 0,
              "min_amount": 1,
              "max_amount": 1000000
            },
            "sep38": {
              "exchangeable_assets": [
                "stellar:JPYC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5",
                "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              ],
              "country_codes": [
                "USA"
              ],
              "sell_delivery_methods": [
                {
                  "name": "WIRE",
                  "description": "Send USD directly to the Anchor\u0027s bank account."
                }
              ],
              "buy_delivery_methods": [
                {
                  "name": "WIRE",
                  "description": "Have USD sent directly to your bank account."
                }
              ],
              "decimals": 4
            },
            "sep24_enabled": false,
            "sep31_enabled": false,
            "sep38_enabled": true
          },
          {
            "schema": "stellar",
            "code": "native",
            "significant_decimals": 7,
            "deposit": {
              "enabled": true,
              "min_amount": 1,
              "max_amount": 1000000
            },
            "withdraw": {
              "enabled": true,
              "min_amount": 1,
              "max_amount": 1000000
            },
            "send": {
              "fee_fixed": 0,
              "fee_percent": 0,
              "min_amount": 1,
              "max_amount": 1000000
            },
           "sep31": {
              "quotes_supported": true,
              "quotes_required": true,
              "sep12": {
                "sender": {
                  "types": {
                    "sep31-sender": {
                      "description": "U.S. citizens limited to sending payments of less than ${'$'}10,000 in value"
                    },
                    "sep31-large-sender": {
                      "description": "U.S. citizens that do not have sending limits"
                    },
                    "sep31-foreign-sender": {
                      "description": "non-U.S. citizens sending payments of less than ${'$'}10,000 in value"
                    }
                  }
                },
                "receiver": {
                  "types": {
                    "sep31-receiver": {
                      "description": "U.S. citizens receiving USD"
                    },
                    "sep31-foreign-receiver": {
                      "description": "non-U.S. citizens receiving USD"
                    }
                  }
                }
              },
              "fields": {
                "transaction": {
                  "receiver_routing_number": {
                    "description": "routing number of the destination bank account"
                  },
                  "receiver_account_number": {
                    "description": "bank account number of the destination"
                  },
                  "type": {
                    "description": "type of deposit to make",
                    "choices": [
                      "SEPA",
                      "SWIFT"
                    ]
                  }
                }
              }
            },
            "sep38": {
              "exchangeable_assets": [
                "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              ],
              "decimals": 7
            },
            "sep31_enabled": true,
            "sep38_enabled": true
          }
        ]
      }    
  """
      .trimIndent()
}
