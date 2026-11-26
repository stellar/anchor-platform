package org.stellar.anchor.platform.utils

import com.google.gson.JsonSyntaxException
import java.io.Reader
import java.io.StringReader
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.anchor.util.GsonUtils

class BoundedJsonReaderTest {
  private val gson = GsonUtils.getInstance()

  class Holder {
    var a: Int = 0
  }

  private fun reader(
    json: String,
    maxNodes: Int = 1_000_000,
    maxDepth: Int = 1_000,
    arrayLimit: BoundedJsonReader.ArrayLimit? = null,
    source: Reader = StringReader(json),
  ) = BoundedJsonReader(source, maxNodes, maxDepth, arrayLimit)

  private fun readObject(r: BoundedJsonReader): Any? = gson.fromJson(r, Any::class.java)

  @Test
  fun `a body with exactly maxNodes values is read to the end`() {
    // [1,2,3] is one array and three numbers: 4 values
    val r = reader("[1,2,3]", maxNodes = 4)
    assertEquals(listOf(1L, 2L, 3L), readObject(r))
    assertEquals(4, r.nodeCount())
  }

  @Test
  fun `a body with maxNodes plus one values throws at the value over the limit`() {
    val r = reader("[1,2,3]", maxNodes = 3)
    val ex = assertThrows(JsonStructureLimitException::class.java) { readObject(r) }
    assertEquals(JsonStructureLimitException.NODES, ex.limitName)
    assertEquals(3, ex.limit)
  }

  @Test
  fun `scalar kinds all count as nodes`() {
    // object + string + number + boolean + null = 5 values
    val body = """{"s":"x","n":1,"b":true,"z":null}"""
    assertEquals(5, reader(body, maxNodes = 5).also { readObject(it) }.nodeCount())
    assertThrows(JsonStructureLimitException::class.java) { readObject(reader(body, maxNodes = 4)) }
  }

  @Test
  fun `member names are not counted`() {
    // {"a":{"b":{"c":1}}} is three objects and one number: 4 values, 3 names
    val r = reader("""{"a":{"b":{"c":1}}}""", maxNodes = 4)
    readObject(r)
    assertEquals(4, r.nodeCount())
  }

  @Test
  fun `nesting of exactly maxDepth is read`() {
    val r = reader("[[[]]]", maxDepth = 3)
    assertEquals(listOf(listOf(listOf<Any>())), readObject(r))
  }

  @Test
  fun `nesting one level over maxDepth throws at the opening bracket`() {
    val r = reader("[[[]]]", maxDepth = 2)
    val ex = assertThrows(JsonStructureLimitException::class.java) { readObject(r) }
    assertEquals(JsonStructureLimitException.DEPTH, ex.limitName)
    assertEquals(2, ex.limit)
    // the third bracket is the one refused: only the two outer arrays were counted
    assertEquals(2, r.nodeCount())
  }

  @Test
  fun `a root array of exactly the limit is read`() {
    val r = reader("[{},{},{}]", arrayLimit = BoundedJsonReader.ArrayLimit.root(3))
    assertEquals(3, (readObject(r) as List<*>).size)
  }

  @Test
  fun `the element after the root array limit throws before its children are read`() {
    val r =
      reader("""[{},{},{},{"a":[1,2,3,4,5]}]""", arrayLimit = BoundedJsonReader.ArrayLimit.root(3))
    val ex = assertThrows(JsonArrayLimitException::class.java) { readObject(r) }
    assertNull(ex.member)
    assertEquals(3, ex.limit)
    // the outer array and three elements: the fourth element and its children were never counted
    assertEquals(4, r.nodeCount())
  }

  @Test
  fun `the root array limit also stops scalar elements`() {
    val r = reader("[1,2,3]", arrayLimit = BoundedJsonReader.ArrayLimit.root(2))
    assertThrows(JsonArrayLimitException::class.java) { readObject(r) }
  }

  @Test
  fun `a records member array of exactly the limit is read`() {
    val r =
      reader(
        """{"records":[{},{}]}""",
        arrayLimit = BoundedJsonReader.ArrayLimit.member("records", 2),
      )
    assertEquals(2, ((readObject(r) as Map<*, *>)["records"] as List<*>).size)
  }

  @Test
  fun `the record after the member array limit throws and names the member`() {
    val r =
      reader(
        """{"records":[{},{},{}]}""",
        arrayLimit = BoundedJsonReader.ArrayLimit.member("records", 2),
      )
    val ex = assertThrows(JsonArrayLimitException::class.java) { readObject(r) }
    assertEquals("records", ex.member)
    assertEquals(2, ex.limit)
  }

  @Test
  fun `an array under another member name is not limited by the records limit`() {
    val r =
      reader(
        """{"other":[{},{},{}],"records":[{}]}""",
        arrayLimit = BoundedJsonReader.ArrayLimit.member("records", 2),
      )
    readObject(r)
  }

  @Test
  fun `a records array nested below the root object is not limited`() {
    val r =
      reader(
        """{"a":{"records":[{},{},{}]}}""",
        arrayLimit = BoundedJsonReader.ArrayLimit.member("records", 2),
      )
    readObject(r)
  }

  @Test
  fun `a member array limit does not apply to a root array`() {
    val r = reader("[{},{},{}]", arrayLimit = BoundedJsonReader.ArrayLimit.member("records", 1))
    readObject(r)
  }

  @Test
  fun `values inside a member the target type does not declare are not counted`() {
    // object + a's number = 2 values; the ten numbers in "u" are skipped by Gson
    val r = reader("""{"a":1,"u":[1,2,3,4,5,6,7,8,9,10]}""", maxNodes = 2)
    val holder: Holder = gson.fromJson(r, Holder::class.java as java.lang.reflect.Type)
    assertEquals(1, holder.a)
  }

  @Test
  fun `malformed json is reported by gson and not as a limit`() {
    assertThrows(JsonSyntaxException::class.java) { readObject(reader("[1,2", maxNodes = 100)) }
  }

  @Test
  fun `readers do not share counters across threads`() {
    val failures = AtomicInteger()
    val pool = Executors.newFixedThreadPool(4)
    val start = CountDownLatch(1)
    repeat(4) {
      pool.execute {
        start.await()
        repeat(500) {
          try {
            readObject(reader("[1,2,3]", maxNodes = 4))
          } catch (e: Throwable) {
            failures.incrementAndGet()
          }
        }
      }
    }
    start.countDown()
    pool.shutdown()
    assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
    assertEquals(0, failures.get())
  }

  @Test
  fun `the reporter body is refused after allocating less than 2 MiB`() {
    // 330,000 empty objects in params, as in the report (990 KB)
    val body = "{\"params\":[" + List(330_000) { "{}" }.joinToString(",") + "]}"
    assertTrue(body.length > 900_000)
    val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    val id = Thread.currentThread().id
    // warm up so class loading is not measured
    assertThrows(JsonStructureLimitException::class.java) {
      readObject(reader(body, maxNodes = 10_000, maxDepth = 32))
    }
    val before = bean.getThreadAllocatedBytes(id)
    assertThrows(JsonStructureLimitException::class.java) {
      readObject(reader(body, maxNodes = 10_000, maxDepth = 32))
    }
    val allocated = bean.getThreadAllocatedBytes(id) - before
    assertTrue(allocated < 2 * 1024 * 1024, "allocated $allocated bytes")
  }
}
