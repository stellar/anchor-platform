package org.stellar.anchor.dto.sep31

import org.junit.jupiter.api.Test
import org.skyscreamer.jsonassert.JSONAssert
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionRequest
import org.stellar.anchor.util.GsonUtils

internal class Sep31PostTransactionRequestTest {
  private val gson = GsonUtils.getInstance()
  private val postTxnJson =
    """{
    "amount": "10.0",
    "asset_code": "USDC",
    "asset_issuer": "GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN",
    "receiver_id": "RECEIVER_1234",
    "refund_memo": "my-refund-memo",
    "refund_memo_type": "text",
    "fields": {
        "transaction": {
            "receiver_routing_number": "r0123",
            "receiver_account_number": "a0456",
            "type": "SWIFT"
        }
    }
}"""

  @Test
  fun `test parsing JSON string to the Sep31PostTransactionRequest`() {
    val request = gson.fromJson(postTxnJson, Sep31PostTransactionRequest::class.java)
    JSONAssert.assertEquals(postTxnJson, gson.toJson(request), false)
  }
}
