package org.stellar.anchor.platform.integrationtest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.skyscreamer.jsonassert.comparator.CustomComparator
import org.stellar.anchor.util.StringHelper.json

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Sep38PlatformApiTests : PlatformApiTests() {
  @Test
  @Order(10)
  fun `test SEP38 post quote will result in the quote stored in the platform server`() {
    val quote = sep38Client.postQuote(USDC.sep38, "100", "iso4217:USD")
    val fetchedQuote = platformApiClient.getQuote(quote.id)
    assertEquals(quote.id, fetchedQuote.id)
    assertEquals(quote.price, fetchedQuote.price)
    JSONAssert.assertEquals(
      json(quote),
      json(fetchedQuote),
      CustomComparator(JSONCompareMode.LENIENT),
    )
  }
}
