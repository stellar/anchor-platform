package org.stellar.anchor.platform.util

import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.net.Socket
import java.nio.file.Files
import java.util.Random
import kotlin.test.assertTrue
import org.apache.catalina.startup.Tomcat
import org.apache.catalina.util.ServerInfo
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * HackerOne #4243067 (ANCHOR-1591): when Tomcat refuses a request after reading its headers, its
 * error page is rendered in the language of the first `Accept-Language` tag it has text for. On
 * Tomcat 10.1.19 every tag it tried left an entry in the JDK's cache of missing resource bundles,
 * and nothing evicted it, so about 180 refused requests carrying 1,085 new tags each filled a 256
 * MiB heap until the process restarted.
 *
 * The tests run a real embedded Tomcat, the same artifact the SEP and Platform servers embed, and
 * send it raw HTTP, so Tomcat itself parses the encoded slash and the long header. Retention is
 * measured as live heap after full GCs: the leak keeps about 1.4 MiB per request, the fixed Tomcat
 * about 10 KiB.
 */
class TomcatErrorPageLocaleTest {
  companion object {
    // Lowest Tomcat 10.1 release measured with the cap on locales per request.
    private val FIXED_FLOOR = listOf(10, 1, 62)
    private const val REQUESTS = 100
    private const val TAGS_PER_REQUEST = 1085 // 7.6 KB, inside Tomcat's 8 KiB header limit
    private const val TAG_LENGTH = 6
    // 100 requests keep about 139 MiB on 10.1.19 and about 1 MiB on 10.1.62.
    private const val MAX_GROWTH_BYTES = 48L * 1024 * 1024

    private lateinit var tomcat: Tomcat
    private var port = 0

    @BeforeAll
    @JvmStatic
    fun startTomcat() {
      tomcat = Tomcat()
      tomcat.setBaseDir(Files.createTempDirectory("tomcat-locale-test").toString())
      tomcat.setPort(0)
      tomcat.connector // creates the connector
      val context = tomcat.addContext("", Files.createTempDirectory("tomcat-docbase").toString())
      Tomcat.addServlet(context, "ok", OkServlet()).addMapping("/*")
      tomcat.start()
      port = tomcat.connector.localPort
    }

    @AfterAll
    @JvmStatic
    fun stopTomcat() {
      tomcat.stop()
      tomcat.destroy()
    }

    private fun tags(random: Random): String =
      (1..TAGS_PER_REQUEST).joinToString(",") {
        String(CharArray(TAG_LENGTH) { 'a' + random.nextInt(26) })
      }

    /** Sends one GET with the given `Accept-Language` and returns the raw response. */
    private fun get(path: String, acceptLanguage: String): String =
      Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 10_000
        socket
          .getOutputStream()
          .write(
            ("GET $path HTTP/1.1\r\nHost: localhost\r\nAccept-Language: $acceptLanguage\r\n" +
                "Connection: close\r\n\r\n")
              .toByteArray()
          )
        socket.getOutputStream().flush()
        socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
      }

    private fun liveHeap(): Long {
      repeat(2) { System.gc() }
      val runtime = Runtime.getRuntime()
      return runtime.totalMemory() - runtime.freeMemory()
    }

    /**
     * Sends [REQUESTS] refused requests to [path] and returns how much the live heap grew. With
     * [distinctTags] every request has tags no earlier request had; otherwise all share one list.
     *
     * The JDK keeps the cache of missing bundles for the life of the JVM, so tags one test sends
     * are no longer new to the next one: every test must pass a [seed] no other test uses.
     */
    private fun heapGrowth(path: String, distinctTags: Boolean, seed: Long): Long {
      val random = Random(seed)
      val sameTags = tags(Random(seed))
      // Warm up the error page path so one-time class loading is not counted as growth.
      get(path, "en")
      val before = liveHeap()
      repeat(REQUESTS) {
        val response = get(path, if (distinctTags) tags(random) else sameTags)
        assertTrue(response.startsWith("HTTP/1.1 400"), "Expected 400 for $path, got: $response")
      }
      return liveHeap() - before
    }

    private fun mib(bytes: Long) = "%.1f MiB".format(bytes / 1048576.0)
  }

  class OkServlet : HttpServlet() {
    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
      resp.writer.write("ok")
    }
  }

  @Test
  fun `LOCALE-01 refused requests with new Accept-Language tags do not grow the heap`() {
    val growth = heapGrowth("/a%2fb", distinctTags = true, seed = 2)

    assertTrue(
      growth < MAX_GROWTH_BYTES,
      "$REQUESTS refused requests with $TAGS_PER_REQUEST new tags each grew the live heap by " +
        "${mib(growth)} (limit ${mib(MAX_GROWTH_BYTES)}) on Tomcat ${ServerInfo.getServerNumber()}",
    )
  }

  @Test
  fun `LOCALE-02 a backslash in the path is refused without growing the heap either`() {
    val growth = heapGrowth("/a%5cb", distinctTags = true, seed = 4)

    assertTrue(
      growth < MAX_GROWTH_BYTES,
      "$REQUESTS refused requests with $TAGS_PER_REQUEST new tags each grew the live heap by " +
        "${mib(growth)} (limit ${mib(MAX_GROWTH_BYTES)}) on Tomcat ${ServerInfo.getServerNumber()}",
    )
  }

  @Test
  fun `LOCALE-03 control - refused requests repeating the same tags do not grow the heap`() {
    val growth = heapGrowth("/a%2fb", distinctTags = false, seed = 1)

    assertTrue(
      growth < MAX_GROWTH_BYTES,
      "$REQUESTS refused requests repeating the same tags grew the live heap by ${mib(growth)} " +
        "(limit ${mib(MAX_GROWTH_BYTES)}) on Tomcat ${ServerInfo.getServerNumber()}",
    )
  }

  @Test
  fun `LOCALE-04 a refused request with a normal Accept-Language gets the 400 error page`() {
    val response = get("/a%2fb", "en")

    assertTrue(response.startsWith("HTTP/1.1 400"), response)
    assertTrue(response.substringAfter("\r\n\r\n").contains("400"), response)
  }

  @Test
  fun `LOCALE-05 a refused request with 1085 new tags still gets the 400 error page`() {
    val response = get("/a%2fb", tags(Random(3)))

    assertTrue(response.startsWith("HTTP/1.1 400"), response)
    assertTrue(response.substringAfter("\r\n\r\n").contains("400"), response)
  }

  @Test
  fun `LOCALE-06 embedded Tomcat is at least the release with the cap on locales per request`() {
    val version = ServerInfo.getServerNumber().split(".").map { it.toInt() }
    val atLeastFloor =
      version
        .zip(FIXED_FLOOR)
        .firstOrNull { (actual, floor) -> actual != floor }
        ?.let { (a, f) -> a > f }
        ?: true
    assertTrue(
      atLeastFloor,
      "Embedded Tomcat is ${ServerInfo.getServerNumber()}, below the floor ${FIXED_FLOOR.joinToString(".")} " +
        "that stops refused requests from caching one resource bundle per Accept-Language tag " +
        "(HackerOne #4243067)",
    )
  }
}
