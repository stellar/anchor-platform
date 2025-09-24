package org.stellar.anchor

class TestConstants {
  companion object {
    const val TEST_SIGNING_SEED = "SBVEOFAHGJCKGR4AAM7RTDRCP6RMYYV5YUV32ZK7ZD3VPDGGHYLXTZRZ"
    const val TEST_ACCOUNT = "GBLGJA4TUN5XOGTV6WO2BWYUI2OZR5GYQ5PDPCRMQ5XEPJOYWB2X4CJO"
    const val TEST_CUSTOMER_ID = "test-customer-id"
    const val TEST_MEMO = "123"
    const val TEST_WEB_AUTH_DOMAIN = "test.stellar.org"
    const val TEST_CLIENT_DOMAIN = "test.client.stellar.org"
    const val TEST_CLIENT_NAME = "test_client"
    const val TEST_HOME_DOMAIN = "test.stellar.org"
    const val TEST_HOME_DOMAIN_PATTERN = "*.wildcard.stellar.org"
    const val TEST_AMOUNT = "542"
    const val TEST_ASSET = "USDC"
    const val TEST_OFFCHAIN_ASSET = "iso4217:USD"
    const val TEST_ASSET_ISSUER_ACCOUNT_ID =
      "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    const val TEST_ASSET_SEP38_FORMAT = "stellar:$TEST_ASSET:$TEST_ASSET_ISSUER_ACCOUNT_ID"
    const val TEST_TRANSACTION_ID_0 = "c60c62da-bcd6-4423-87b8-0cbd19005422"
    const val TEST_TRANSACTION_ID_1 = "b60c62da-bcd6-4423-87b8-0cbd19005422"
    const val TEST_QUOTE_ID = "test-quote-id"

    const val TEST_CLIENT_TOML =
      "" +
        "       NETWORK_PASSPHRASE=\"Public Global Stellar Network ; September 2015\"\n" +
        "       HORIZON_URL=\"https://horizon.stellar.org\"\n" +
        "       FEDERATION_SERVER=\"https://preview.lobstr.co/federation/\"\n" +
        "       SIGNING_KEY=\"GACYKME36AI6UYAV7A5ZUA6MG4C4K2VAPNYMW5YLOM6E7GS6FSHDPV4F\"\n"
  }
}
