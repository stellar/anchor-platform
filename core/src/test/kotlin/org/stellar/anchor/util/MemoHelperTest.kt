package org.stellar.anchor.util

import java.util.*
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.api.exception.SepException
import org.stellar.anchor.api.exception.SepValidationException
import org.stellar.anchor.util.MemoHelper.*
import org.stellar.sdk.MemoHash
import org.stellar.sdk.MemoId
import org.stellar.sdk.MemoReturnHash
import org.stellar.sdk.MemoText
import org.stellar.sdk.xdr.MemoType.*

private const val MEMO_VALUE_1 = "edb2690440c820de8df242beb313d8ec48cf628ac1e6804939e94c1c2f511ba0"
private const val MEMO_VALUE_2 = "12345678901234567890123456789012"
private const val MEMO_VALUE_HASH_2 =
  "3132333435363738393031323334353637383930313233343536373839303132"

internal class MemoHelperTest {
  @Test
  fun `Test makeMemo error`() {
    assertThrows<SepValidationException> { makeMemo("memo", "bad_type") }
    assertThrows<SepValidationException> { makeMemo("bad_number", "id") }
    assertThrows<IllegalArgumentException> { makeMemo("bad_hash", "hash") }
    assertThrows<SepException> { makeMemo("none", "none") }
    assertThrows<SepException> { makeMemo("return", "return") }
    assertThrows<SepException> { makeMemo("none", MEMO_NONE) }
  }

  @Test
  fun `test memo hash conversion`() {
    val wantHex = "39623738663066612d393366392d343139382d386439332d6537366664303834"
    val gotHex = convertBase64ToHex("OWI3OGYwZmEtOTNmOS00MTk4LThkOTMtZTc2ZmQwODQ=")
    assertEquals(wantHex, gotHex)

    val wantBase64 = "OWI3OGYwZmEtOTNmOS00MTk4LThkOTMtZTc2ZmQwODQ="
    val gotBase64 =
      convertHexToBase64("39623738663066612d393366392d343139382d386439332d6537366664303834")
    assertEquals(wantBase64, gotBase64)
  }

  @Test
  fun `test memoAsString with different memo type`() {
    assertEquals("1234", memoAsString(makeMemo("1234", MEMO_ID)))
    assertEquals("TEXT_1234", memoAsString(makeMemo("TEXT_1234", MEMO_TEXT)))
    assertEquals(MEMO_VALUE_1, memoAsString(MemoHash(MEMO_VALUE_1)))
    assertEquals(MEMO_VALUE_1, memoAsString(MemoReturnHash(MEMO_VALUE_1)))

    assertEquals(
      MEMO_VALUE_HASH_2,
      memoAsString(
        makeMemo(Base64.getEncoder().encodeToString(MEMO_VALUE_2.encodeToByteArray()), MEMO_HASH)
      )
    )

    assertEquals(
      MEMO_VALUE_HASH_2,
      memoAsString(
        makeMemo(Base64.getEncoder().encodeToString(MEMO_VALUE_2.encodeToByteArray()), MEMO_RETURN)
      )
    )
  }

  @Test
  fun `test toXdr()`() {
    val memoId: MemoId = makeMemo("123", MEMO_ID) as MemoId
    assertEquals("123", "${toXdr(memoId).id.uint64.number}")

    val memoText: MemoText = makeMemo("memo text", MEMO_TEXT) as MemoText
    assertEquals("memo text", "${toXdr(memoText).text}")
  }
}
