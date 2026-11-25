package org.stellar.anchor.platform.util

import kotlin.system.measureNanoTime
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.SpringVersion
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.StringHttpMessageConverter
import org.springframework.http.converter.json.GsonHttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.ServletWebRequest
import org.stellar.anchor.config.Sep1Config.TomlType.STRING
import org.stellar.anchor.platform.config.PropertySep1Config
import org.stellar.anchor.platform.config.PropertySep1Config.TomlConfig
import org.stellar.anchor.platform.controller.platform.PlatformHealthController
import org.stellar.anchor.platform.controller.sep.Sep1Controller
import org.stellar.anchor.platform.service.HealthCheckService
import org.stellar.anchor.sep1.Sep1Service
import org.stellar.anchor.util.GsonUtils

/**
 * HackerOne #4202947 (ANCHOR-1539): Spring Framework must carry the fix for CVE-2024-38809. Before
 * 6.1.12, `ServletWebRequest` parsed `If-None-Match` with a regex whose cost grows with the square
 * of the header length, on every 200 GET/HEAD answered with a `ResponseEntity`, even when the
 * response has no ETag.
 *
 * The requests run through `MockMvc` over the real controllers, so the real argument and
 * return-value handlers (`HttpEntityMethodProcessor`) and `ServletWebRequest` do the work. The
 * header is built in-process, which skips Tomcat's 8 KB header limit and lets the padding be large
 * enough that the vulnerable parser needs seconds while the fixed one needs milliseconds.
 */
class SpringEtagRegexTest {
  companion object {
    // First Spring Framework 6.1 release with the fix (ETag.parse replaces the regex).
    private val FIXED_FLOOR = listOf(6, 1, 12)

    // The report's request: "x", 7,950 spaces, "x" (8,000 bytes in all).
    private const val REPORT_PADDING = 7_950

    // Scaled up so the vulnerable regex needs several seconds (quadratic in the padding).
    private const val LARGE_PADDING = 40_000

    private const val TIME_BOUND_MS = 2_000L

    private const val TOML = "VERSION = \"0.1.0\""

    private fun crafted(padding: Int) = "x" + " ".repeat(padding) + "x"

    private fun toml(): MockMvc {
      val config = PropertySep1Config(true, TomlConfig(STRING, TOML))
      return MockMvcBuilders.standaloneSetup(Sep1Controller(config, Sep1Service(config)))
        .setMessageConverters(
          StringHttpMessageConverter(Charsets.UTF_8),
          GsonHttpMessageConverter(GsonUtils.getInstance()),
        )
        .build()
    }

    private fun health(): MockMvc =
      MockMvcBuilders.standaloneSetup(PlatformHealthController(HealthCheckService(listOf())))
        .setMessageConverters(
          StringHttpMessageConverter(Charsets.UTF_8),
          GsonHttpMessageConverter(GsonUtils.getInstance()),
        )
        .build()

    private fun etagServer(): MockMvc =
      MockMvcBuilders.standaloneSetup(EtagController())
        .setMessageConverters(StringHttpMessageConverter(Charsets.UTF_8))
        .build()

    private fun atLeastFloor(version: String?): Boolean {
      val parts = (version ?: return false).split(".", "-").mapNotNull { it.toIntOrNull() }
      for (i in FIXED_FLOOR.indices) {
        val actual = parts.getOrElse(i) { 0 }
        if (actual != FIXED_FLOOR[i]) return actual > FIXED_FLOOR[i]
      }
      return true
    }

    /** Runs the request and returns the result with the wall-clock time it took. */
    private fun timed(
      mvc: MockMvc,
      path: String,
      header: String,
      value: String
    ): Pair<MvcResult, Long> {
      lateinit var result: MvcResult
      val nanos = measureNanoTime {
        result = mvc.perform(get(path).header(header, value)).andReturn()
      }
      return Pair(result, nanos / 1_000_000)
    }
  }

  @RestController
  class EtagController {
    @GetMapping("/resource")
    fun resource(): ResponseEntity<String> = ResponseEntity.ok().eTag("\"abc\"").body("hello")
  }

  // SPRING-03
  @Test
  fun `test the running Spring Web is at least the fixed release`() {
    val core = SpringVersion.getVersion()
    val web = ServletWebRequest::class.java.`package`.implementationVersion
    assertTrue(atLeastFloor(core)) {
      "spring-core is $core, below the fixed floor ${FIXED_FLOOR.joinToString(".")}"
    }
    assertTrue(atLeastFloor(web)) {
      "spring-web is $web, below the fixed floor ${FIXED_FLOOR.joinToString(".")}"
    }
  }

  // SPRING-04, and the report's own size (edge case)
  @ParameterizedTest
  @ValueSource(ints = [REPORT_PADDING, LARGE_PADDING])
  fun `test a crafted If-None-Match on the TOML is answered 200 without the quadratic cost`(
    padding: Int
  ) {
    val (result, ms) = timed(toml(), "/.well-known/stellar.toml", "If-None-Match", crafted(padding))

    assertEquals(200, result.response.status, "If-None-Match padded with $padding spaces")
    assertEquals(TOML, result.response.contentAsString)
    assertTrue(ms < TIME_BOUND_MS) { "$padding spaces took $ms ms, bound $TIME_BOUND_MS ms" }
  }

  // SPRING-05
  @Test
  fun `test a crafted If-None-Match on the health controller is answered 200 without the cost`() {
    val (result, ms) = timed(health(), "/health", "If-None-Match", crafted(LARGE_PADDING))

    assertEquals(200, result.response.status)
    assertTrue(ms < TIME_BOUND_MS) { "$LARGE_PADDING spaces took $ms ms, bound $TIME_BOUND_MS ms" }
  }

  // SPRING-06
  @Test
  fun `test a crafted If-Match on the TOML is answered 200 without the quadratic cost`() {
    val (result, ms) =
      timed(toml(), "/.well-known/stellar.toml", "If-Match", crafted(LARGE_PADDING))

    assertEquals(200, result.response.status)
    assertTrue(ms < TIME_BOUND_MS) { "$LARGE_PADDING spaces took $ms ms, bound $TIME_BOUND_MS ms" }
  }

  // SPRING-07
  @Test
  fun `test a matching If-None-Match answers 304 with no body`() {
    val (result, _) = timed(etagServer(), "/resource", "If-None-Match", "\"abc\"")

    assertEquals(304, result.response.status)
    assertEquals("", result.response.contentAsString)
  }

  // SPRING-08
  @Test
  fun `test a non-matching If-None-Match answers 200 with the body`() {
    val (result, _) = timed(etagServer(), "/resource", "If-None-Match", "\"other\"")

    assertEquals(200, result.response.status)
    assertEquals("hello", result.response.contentAsString)
  }

  // SPRING-09
  @Test
  fun `test If-None-Match star answers 200 with the body as before the upgrade`() {
    // Observed on 6.1.5 and on 6.1.21: Spring does not treat the wildcard as a match here.
    val (result, _) = timed(etagServer(), "/resource", "If-None-Match", "*")

    assertEquals(200, result.response.status)
    assertEquals("hello", result.response.contentAsString)
  }
}
