package org.stellar.anchor

class Constants {
  companion object {
    const val TEST_SIGNING_SEED = "SBVEOFAHGJCKGR4AAM7RTDRCP6RMYYV5YUV32ZK7ZD3VPDGGHYLXTZRZ"
    const val TEST_ACCOUNT = "GBLGJA4TUN5XOGTV6WO2BWYUI2OZR5GYQ5PDPCRMQ5XEPJOYWB2X4CJO"
    const val TEST_MEMO = "123"
    const val TEST_HOME_DOMAIN = "test.stellar.org"
    const val TEST_CLIENT_DOMAIN = "test.client.stellar.org"
    const val TEST_NETWORK_PASS_PHRASE = "Test SDF Network ; September 2015"
    const val TEST_HOST_URL = "https://test.stellar.org"
    const val TEST_JWT_SECRET = "secret_secret_secret_secret_secret_secret_secret_secret"
    const val TEST_ASSET = "USDC"
    const val TEST_ASSET_ISSUER_ACCOUNT_ID =
      "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
    const val TEST_TRANSACTION_ID_0 = "c60c62da-bcd6-4423-87b8-0cbd19005422"
    const val TEST_TRANSACTION_ID_1 = "b60c62da-bcd6-4423-87b8-0cbd19005422"

    const val TEST_CLIENT_TOML =
      "" +
        "       NETWORK_PASSPHRASE=\"Public Global Stellar Network ; September 2015\"\n" +
        "       HORIZON_URL=\"https://horizon.stellar.lobstr.co\"\n" +
        "       FEDERATION_SERVER=\"https://preview.lobstr.co/federation/\"\n" +
        "       SIGNING_KEY=\"GACYKME36AI6UYAV7A5ZUA6MG4C4K2VAPNYMW5YLOM6E7GS6FSHDPV4F\"\n"
  }
}
