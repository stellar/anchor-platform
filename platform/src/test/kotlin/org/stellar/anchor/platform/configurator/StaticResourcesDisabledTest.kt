package org.stellar.anchor.platform.configurator

import java.net.URI
import java.util.Random
import java.util.concurrent.atomic.AtomicLong
import java.util.stream.Stream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.gson.GsonAutoConfiguration
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletConfig
import org.springframework.mock.web.MockServletContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.DispatcherServlet
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler
import org.stellar.anchor.config.Sep1Config.TomlType.STRING
import org.stellar.anchor.platform.config.PropertySep1Config
import org.stellar.anchor.platform.config.PropertySep1Config.TomlConfig
import org.stellar.anchor.platform.controller.sep.Sep1Controller
import org.stellar.anchor.platform.controller.sep.SepControllerExceptionHandler
import org.stellar.anchor.sep1.Sep1Service

/**
 * HackerOne #4245484 (ANCHOR-1594). Spring Boot's default catch-all static-resource handler builds
 * a `java.net.URL` from the text before a `:/` in an unmatched path; with
 * `java.protocol.handler.pkgs` set (the Boot launcher sets it) the JDK keeps one class-loading lock
 * per tried name, so every distinct 8 KB path stays on the heap. None of the four servers serves
 * static files, so each adapter turns the handler off.
 *
 * The context is Boot's real MVC auto-configuration with the adapter's own properties applied, the
 * real exception advice and the real SEP-1 controller. The full server cannot be slice-tested (see
 * `AbstractControllerExceptionHandlerTest`); the reporter's container run is not reproduced here.
 */
class StaticResourcesDisabledTest {
  /** `Sep1Controller` is conditional on the SEP configuration, so it is declared by hand. */
  @Configuration
  open class Sep1Routes {
    @Bean
    open fun sep1Controller(): Sep1Controller {
      val config = PropertySep1Config(true, TomlConfig(STRING, "VERSION = \"0.1.0\""))
      return Sep1Controller(config, Sep1Service(config))
    }
  }

  companion object {
    private const val PROPERTY = "spring.web.resources.add-mappings"
    private const val HANDLER_PACKAGES = "java.protocol.handler.pkgs"
    private const val REQUESTS = 1_500
    private const val PATH_LETTERS = 8_000
    private const val HEAP_GROWTH_LIMIT = 2L * 1024 * 1024

    /** The JDK keeps every tried name for the whole JVM, so no two heap tests may share paths. */
    private val seeds = AtomicLong(7)

    @JvmStatic
    fun adapters(): Stream<Arguments> =
      Stream.of(
        Arguments.of("SEP server", SepServerConfigAdapter()),
        Arguments.of("Platform server", PlatformServerConfigAdapter()),
        Arguments.of("Observer", ObserverConfigAdapter()),
        Arguments.of("Event Processor", EventProcessorConfigAdapter()),
      )
  }

  private val toml = "VERSION = \"0.1.0\""
  private var savedHandlerPackages: String? = null

  @BeforeEach
  fun setHandlerPackages() {
    // What the Spring Boot launcher does: a package name the JDK tries for every URL protocol.
    savedHandlerPackages = System.getProperty(HANDLER_PACKAGES)
    System.setProperty(HANDLER_PACKAGES, "org.springframework.boot.loader")
  }

  @AfterEach
  fun restoreHandlerPackages() {
    val saved = savedHandlerPackages
    if (saved == null) System.clearProperty(HANDLER_PACKAGES)
    else System.setProperty(HANDLER_PACKAGES, saved)
  }

  private fun runner(adapter: SpringConfigAdapter?, vararg properties: String) =
    WebApplicationContextRunner()
      .withInitializer { context ->
        if (adapter != null) adapter.updateSpringEnv(context, ConfigHelper.loadDefaultConfig())
      }
      .withPropertyValues(*properties)
      .withConfiguration(
        AutoConfigurations.of(
          DispatcherServletAutoConfiguration::class.java,
          WebMvcAutoConfiguration::class.java,
          HttpMessageConvertersAutoConfiguration::class.java,
          GsonAutoConfiguration::class.java,
        )
      )
      .withBean(SepControllerExceptionHandler::class.java)
      .withUserConfiguration(Sep1Routes::class.java)

  private fun mvc(context: WebApplicationContext): MockMvc =
    MockMvcBuilders.webAppContextSetup(context).build()

  private fun resourceHandlers(context: ConfigurableApplicationContext): List<Any> =
    context.getBeansOfType(HandlerMapping::class.java).values.flatMap { mapping ->
      if (mapping is AbstractUrlHandlerMapping) mapping.handlerMap.values.toList() else listOf()
    }

  private fun liveHeap(): Long {
    System.gc()
    System.gc()
    val runtime = Runtime.getRuntime()
    return runtime.totalMemory() - runtime.freeMemory()
  }

  private fun randomLetters(random: Random) =
    String(CharArray(PATH_LETTERS) { 'a' + random.nextInt(26) })

  /** Sends [REQUESTS] new paths of [PATH_LETTERS] letters and returns the live heap growth. */
  private fun heapGrowth(mvc: MockMvc, requestFor: (String) -> RequestBuilder): Long {
    val random = Random(seeds.getAndIncrement())
    mvc.perform(get("/warm:/x")).andReturn()
    val before = liveHeap()
    // The test is vacuous unless it sends about as many bytes as the unfixed server retains.
    assertTrue(REQUESTS.toLong() * PATH_LETTERS >= 12_000_000L)
    repeat(REQUESTS) {
      val request = requestFor(randomLetters(random))
      val uri = (request.buildRequest(MockServletContext()) as MockHttpServletRequest).requestURI
      assertTrue(
        uri.contains(":/") || uri.contains("%3A%2F"),
        "the path must contain a colon-slash"
      )
      mvc.perform(request).andReturn()
    }
    return liveHeap() - before
  }

  // STATIC-01, STATIC-02, STATIC-03, STATIC-04, STATIC-16
  @ParameterizedTest(name = "{0}")
  @MethodSource("adapters")
  fun `every adapter turns the static-resource handler off with the default configuration`(
    name: String,
    adapter: SpringConfigAdapter
  ) {
    WebApplicationContextRunner()
      .withInitializer { context ->
        adapter.updateSpringEnv(context, ConfigHelper.loadDefaultConfig())
      }
      .run { context ->
        assertEquals(false, context.environment.getProperty(PROPERTY, Boolean::class.java))
      }
  }

  // STATIC-05 (the control: without the adapter Boot registers the handler)
  @Test
  fun `without the adapter Boot registers the resource handler`() {
    runner(null).run { context ->
      assertTrue(resourceHandlers(context).any { it is ResourceHttpRequestHandler })
    }
  }

  // STATIC-05
  @ParameterizedTest(name = "{0}")
  @MethodSource("adapters")
  fun `no resource handler is registered`(name: String, adapter: SpringConfigAdapter) {
    runner(adapter).run { context ->
      assertFalse(resourceHandlers(context).any { it is ResourceHttpRequestHandler })
    }
  }

  // STATIC-15
  @ParameterizedTest(name = "{0}")
  @MethodSource("adapters")
  fun `an environment value cannot turn the handler back on`(
    name: String,
    adapter: SpringConfigAdapter
  ) {
    runner(adapter, "$PROPERTY=true").run { context ->
      assertFalse(resourceHandlers(context).any { it is ResourceHttpRequestHandler })
    }
  }

  // STATIC-06, STATIC-09
  @ParameterizedTest(name = "{0}")
  @MethodSource("adapters")
  fun `the reporter's path answers 404 JSON`(name: String, adapter: SpringConfigAdapter) {
    runner(adapter).run { context ->
      val path = "/" + "a".repeat(PATH_LETTERS) + ":/../x"
      mvc(context)
        .perform(get(path))
        .andExpect(status().isNotFound)
        .andExpect(jsonPath("$.error").isString)
    }
  }

  // STATIC-07
  @ParameterizedTest(name = "{0}")
  @MethodSource("adapters")
  fun `the reporter's requests do not grow the heap`(name: String, adapter: SpringConfigAdapter) {
    runner(adapter).run { context ->
      val growth = heapGrowth(mvc(context)) { letters -> get("/$letters:/../x") }
      assertTrue(growth < HEAP_GROWTH_LIMIT, "live heap grew by $growth bytes")
    }
  }

  // STATIC-14
  @Test
  fun `the encoded form of the path does not grow the heap`() {
    runner(SepServerConfigAdapter()).run { context ->
      val mvc = mvc(context)
      val growth = heapGrowth(mvc) { letters -> get(URI.create("/$letters%3A%2F..%2Fx")) }
      assertTrue(growth < HEAP_GROWTH_LIMIT, "live heap grew by $growth bytes")
      mvc.perform(get(URI.create("/a%3A%2F..%2Fx"))).andExpect(status().isNotFound)
    }
  }

  // STATIC-08
  @Test
  fun `a mapped route is still served`() {
    runner(SepServerConfigAdapter()).run { context ->
      mvc(context)
        .perform(get("/.well-known/stellar.toml").accept(MediaType.TEXT_PLAIN))
        .andExpect(status().isOk)
        .andExpect(content().string(toml))
    }
  }

  // STATIC-09, STATIC-10
  @ParameterizedTest(name = "{0}")
  @MethodSource("adapters")
  fun `an unmatched path answers 404 with the JSON error shape and never 500`(
    name: String,
    adapter: SpringConfigAdapter
  ) {
    runner(adapter).run { context ->
      mvc(context)
        .perform(get("/nope"))
        .andExpect(status().isNotFound)
        .andExpect(jsonPath("$.error").isString)
    }
  }

  /**
   * MockMvc builds its own servlet and always throws on a missing handler, which Boot's does not
   * unless told to. This drives the servlet bean the real servers get from Boot.
   */
  private fun bootServletAnswer(
    context: ConfigurableApplicationContext,
    path: String
  ): MockHttpServletResponse {
    val servlet = context.getBean(DispatcherServlet::class.java)
    val servletContext = (context as WebApplicationContext).servletContext
    servlet.init(MockServletConfig(servletContext))
    val response = MockHttpServletResponse()
    servlet.service(MockHttpServletRequest(servletContext, "GET", path), response)
    return response
  }

  // STATIC-09, STATIC-10 through the servlet Boot configures
  @ParameterizedTest(name = "{0}")
  @MethodSource("adapters")
  fun `Boot's own servlet answers an unmatched path with the JSON 404`(
    name: String,
    adapter: SpringConfigAdapter
  ) {
    runner(adapter).run { context ->
      val response = bootServletAnswer(context, "/nope")
      assertEquals(404, response.status)
      assertTrue(response.contentAsString.contains("\"error\""), response.contentAsString)
    }
  }

  // STATIC-11
  @Test
  fun `a wrong method on a mapped path answers what it answers without the adapter`() {
    var control = 0
    runner(null).run { context ->
      control = mvc(context).perform(post("/.well-known/stellar.toml")).andReturn().response.status
    }
    runner(SepServerConfigAdapter()).run { context ->
      mvc(context).perform(post("/.well-known/stellar.toml")).andExpect(status().`is`(control))
    }
  }

  // STATIC-12
  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = ["/favicon.ico", "/index.html"])
  fun `the usual static names answer 404`(path: String) {
    runner(SepServerConfigAdapter()).run { context ->
      mvc(context).perform(get(path)).andExpect(status().isNotFound)
    }
  }

  // STATIC-17
  @Test
  fun `the shipped log configuration keeps unmatched paths out of the warnings`() {
    val stream = javaClass.getResourceAsStream("/log4j2.yaml")
    assertNotNull(stream, "log4j2.yaml is not on the classpath")
    val root = org.yaml.snakeyaml.Yaml().load<Map<String, Any>>(stream)
    @Suppress("UNCHECKED_CAST")
    val loggers = (root["Configutation"] as Map<String, Any>)["Loggers"] as Map<String, Any>
    @Suppress("UNCHECKED_CAST") val entries = loggers["Logger"] as List<Map<String, String>>
    val level = entries.single { it["name"] == "org.springframework.web.servlet.PageNotFound" }
    assertEquals("error", level["level"])
  }

  // STATIC-13
  @Test
  fun `a colon-slash after the first segment answers 404`() {
    runner(SepServerConfigAdapter()).run { context ->
      mvc(context).perform(get("/a/b:/../x")).andExpect(status().isNotFound)
    }
  }
}
