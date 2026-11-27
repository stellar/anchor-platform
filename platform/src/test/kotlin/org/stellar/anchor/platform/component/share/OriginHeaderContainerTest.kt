package org.stellar.anchor.platform.component.share

import io.mockk.unmockkAll
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.WebApplicationType
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.web.servlet.ServletManagementContextAutoConfiguration
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * HackerOne #4240773, the two routes the MockMvc tests cannot reach: Tomcat answers a path under
 * `/WEB-INF/` with 404 before the filter chain and forwards it to `/error`, and the actuator runs
 * on its own management port in a child context that does not inherit the parent's filters. Both
 * are exercised on a real embedded Tomcat with the registration the servers use
 * ([OriginHeaderBeans]). The header is the report's shape at 404 characters: about 150 ms of Spring
 * parse without the guard, so a red run finishes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OriginHeaderContainerTest {
  @Configuration(proxyBeanMethods = false)
  @ImportAutoConfiguration(
    ServletWebServerFactoryAutoConfiguration::class,
    DispatcherServletAutoConfiguration::class,
    WebMvcAutoConfiguration::class,
    ErrorMvcAutoConfiguration::class,
    HttpMessageConvertersAutoConfiguration::class,
    ManagementContextAutoConfiguration::class,
    ServletManagementContextAutoConfiguration::class,
    EndpointAutoConfiguration::class,
    WebEndpointAutoConfiguration::class,
    HealthContributorAutoConfiguration::class,
    HealthEndpointAutoConfiguration::class,
  )
  @Import(OriginHeaderBeans::class, PingController::class)
  class App

  @RestController
  @CrossOrigin(origins = ["*"])
  class PingController {
    @GetMapping("/ping") fun ping() = "pong"
  }

  private val attackOrigin = "//" + "@".repeat(400) + "#" + '\u0085'
  private val rejection = """{"error":"Invalid Origin header."}"""

  private lateinit var context: ConfigurableApplicationContext
  private lateinit var main: String
  private lateinit var management: String
  private val client = HttpClient.newHttpClient()

  /**
   * A static MockK mock of a JDK class (`java.util.Calendar`, `TimeZone`, `Instant`, ...) that an
   * earlier test class never undoes keeps the class instrumented for the rest of the Gradle test
   * JVM and keeps Tomcat from starting (`NoClassDefFoundError: Rfc6265CookieProcessor`).
   * `PlatformIntegrationHelperTest` leaks `Calendar` this way. Undo every mock before starting a
   * server; this class mocks nothing itself.
   */
  private fun launch(vararg properties: String): ConfigurableApplicationContext {
    unmockkAll()
    return SpringApplicationBuilder(App::class.java)
      .web(WebApplicationType.SERVLET)
      .properties(
        "server.port=0",
        "management.endpoints.web.exposure.include=health",
        "spring.main.banner-mode=off",
        *properties,
      )
      .run()
  }

  @BeforeAll
  fun start() {
    context = launch("management.server.port=0")
    main = "http://localhost:" + context.environment.getProperty("local.server.port")
    management = "http://localhost:" + context.environment.getProperty("local.management.port")
  }

  @AfterAll
  fun stop() {
    context.close()
  }

  private class Answer(val status: Int, val body: String, val millis: Long)

  private fun send(
    base: String,
    path: String,
    method: String = "GET",
    origin: String? = null,
    preflight: Boolean = false,
  ): Answer {
    val builder = HttpRequest.newBuilder(URI.create(base + path))
    builder.method(method, HttpRequest.BodyPublishers.noBody())
    if (origin != null) builder.header("Origin", origin)
    if (preflight) builder.header("Access-Control-Request-Method", "GET")
    val started = System.nanoTime()
    val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    return Answer(response.statusCode(), response.body(), (System.nanoTime() - started) / 1_000_000)
  }

  // ORIGIN-15
  @Test
  fun `a preflight to a path Tomcat answers with 404 before the filters is refused`() {
    val answer = send(main, "/WEB-INF/x", "OPTIONS", attackOrigin, preflight = true)

    assertEquals(400, answer.status, "status; body: ${answer.body.take(200)}")
    assertEquals(rejection, answer.body)
  }

  // ORIGIN-15
  @Test
  fun `a plain request with the attack origin to such a path is refused`() {
    val answer = send(main, "/WEB-INF/x", "GET", attackOrigin)

    assertEquals(400, answer.status, "status; body: ${answer.body.take(200)}")
    assertEquals(rejection, answer.body)
  }

  // ORIGIN-15 (unchanged behavior: no Origin means the error page as before)
  @Test
  fun `a request to such a path without an origin still gets Tomcat's 404`() {
    assertEquals(404, send(main, "/WEB-INF/x").status)
  }

  // ORIGIN-15 (the accepted side: a valid origin is not refused on the error forward)
  @Test
  fun `a preflight with a valid origin to such a path is not refused by the guard`() {
    val answer = send(main, "/WEB-INF/x", "OPTIONS", "https://wallet.example", preflight = true)

    assertNotEquals(400, answer.status, "the guard refused a valid origin: ${answer.body}")
  }

  // ORIGIN-16
  @Test
  fun `the actuator port refuses a preflight with the attack origin`() {
    val answer = send(management, "/actuator/health", "OPTIONS", attackOrigin, preflight = true)

    assertEquals(400, answer.status, "status; body: ${answer.body.take(200)}")
    assertEquals(rejection, answer.body)
  }

  // ORIGIN-16
  @Test
  fun `the actuator port refuses a plain request with the attack origin`() {
    val answer = send(management, "/actuator/health", "GET", attackOrigin)

    assertEquals(400, answer.status, "status; body: ${answer.body.take(200)}")
    assertEquals(rejection, answer.body)
  }

  // ORIGIN-15 and ORIGIN-16: the error forward on the management port
  @Test
  fun `the actuator port refuses the attack origin on a path Tomcat answers with 404`() {
    for (method in listOf("GET", "OPTIONS")) {
      val answer =
        send(management, "/WEB-INF/x", method, attackOrigin, preflight = method == "OPTIONS")

      assertEquals(400, answer.status, "$method; body: ${answer.body.take(200)}")
      assertEquals(rejection, answer.body)
    }
  }

  // ORIGIN-16 (unchanged behavior on the actuator port)
  @Test
  fun `the actuator health answers with and without a valid origin`() {
    assertEquals(200, send(management, "/actuator/health").status)
    assertEquals(
      200,
      send(management, "/actuator/health", origin = "https://wallet.example").status
    )
  }

  // ORIGIN-14 on a real container: the main port still serves a wallet origin
  @Test
  fun `the main port serves a controller to a valid origin and refuses the attack origin`() {
    assertEquals(200, send(main, "/ping", origin = "https://wallet.example").status)
    val refused = send(main, "/ping", origin = attackOrigin)
    assertEquals(400, refused.status)
    assertTrue(refused.millis < 1000) { "took ${refused.millis} ms" }
  }

  // ORIGIN-16 (no separate management port: the actuator is served by the main context, which the
  // main registration already covers; the management registration must not add a second one)
  @Test
  fun `the actuator on the main port is guarded once and still answers a valid origin`() {
    val shared = launch()
    try {
      val port = "http://localhost:" + shared.environment.getProperty("local.server.port")

      assertEquals(
        1,
        shared.getBeansOfType(FilterRegistrationBean::class.java).count {
          it.key == "originHeaderFilter"
        }
      )
      for (method in listOf("GET", "OPTIONS")) {
        val refused =
          send(port, "/actuator/health", method, attackOrigin, preflight = method == "OPTIONS")
        assertEquals(400, refused.status, "$method; body: ${refused.body.take(200)}")
        assertEquals(rejection, refused.body)
      }
      assertEquals(200, send(port, "/actuator/health", origin = "https://wallet.example").status)
      assertEquals(200, send(port, "/actuator/health").status)
    } finally {
      shared.close()
    }
  }
}
