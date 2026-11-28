package org.stellar.anchor.platform.util

import jakarta.servlet.MultipartConfigElement
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.catalina.startup.Tomcat
import org.apache.catalina.util.ServerInfo
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * HackerOne #4199186 (ANCHOR-1537): the embedded Tomcat must carry the fixes for CVE-2025-48976 and
 * CVE-2025-48988, which let a small multipart body cost about thirty times its size in heap.
 *
 * The tests run a real embedded Tomcat, the same artifact the SEP and Platform servers embed, and
 * send it real multipart requests.
 */
class TomcatMultipartTest {
  companion object {
    // First Tomcat 10.1 release with the multipart fixes.
    private val FIXED_FLOOR = listOf(10, 1, 42)
    private const val BOUNDARY = "anchor1537"

    private lateinit var tomcat: Tomcat
    private val client = HttpClient.newHttpClient()

    @BeforeAll
    @JvmStatic
    fun startTomcat() {
      tomcat = Tomcat()
      tomcat.setBaseDir(Files.createTempDirectory("tomcat-multipart-test").toString())
      tomcat.setPort(0)
      tomcat.connector // creates the connector
      val context = tomcat.addContext("", Files.createTempDirectory("tomcat-docbase").toString())
      val wrapper = Tomcat.addServlet(context, "multipart", PartsEchoServlet())
      wrapper.multipartConfigElement = MultipartConfigElement("")
      wrapper.addMapping("/*")
      tomcat.start()
    }

    @AfterAll
    @JvmStatic
    fun stopTomcat() {
      tomcat.stop()
      tomcat.destroy()
    }

    private fun post(body: String): HttpResponse<String> {
      val request =
        HttpRequest.newBuilder(URI("http://localhost:${tomcat.connector.localPort}/"))
          .header("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build()
      return client.send(request, HttpResponse.BodyHandlers.ofString())
    }
  }

  /** Answers `PARTS:<name>=<content>;...` when Tomcat parses the body, else `REJECTED:<cause>`. */
  class PartsEchoServlet : HttpServlet() {
    override fun doPost(req: HttpServletRequest, resp: HttpServletResponse) {
      try {
        val parts =
          req.parts.joinToString(";") { "${it.name}=${String(it.inputStream.readBytes())}" }
        resp.writer.write("PARTS:$parts")
      } catch (t: Throwable) {
        resp.status = HttpServletResponse.SC_BAD_REQUEST
        resp.writer.write(
          "REJECTED:" + generateSequence(t) { it.cause }.joinToString(">") { it::class.java.name }
        )
      }
    }
  }

  @Test
  fun `TOMCAT-03 embedded Tomcat is at least the first release with the multipart fixes`() {
    val version = ServerInfo.getServerNumber().split(".").map { it.toInt() }
    val atLeastFloor =
      version
        .zip(FIXED_FLOOR)
        .firstOrNull { (actual, floor) -> actual != floor }
        ?.let { (a, f) -> a > f }
        ?: true
    assertTrue(
      atLeastFloor,
      "Embedded Tomcat is ${ServerInfo.getServerNumber()}, below the fixed floor ${FIXED_FLOOR.joinToString(".")}",
    )
  }

  @Test
  fun `TOMCAT-04 a part carrying 1675 empty headers is rejected by the multipart parser`() {
    val emptyHeaders = "a:\r\n".repeat(1675)
    val body =
      "--$BOUNDARY\r\n" +
        "Content-Disposition: form-data; name=\"f\"\r\n" +
        emptyHeaders +
        "\r\nv\r\n" +
        "--$BOUNDARY--\r\n"

    val response = post(body)

    assertEquals(400, response.statusCode(), response.body())
    assertEquals(
      "REJECTED:java.lang.IllegalStateException>" +
        "org.apache.tomcat.util.http.fileupload.impl.SizeLimitExceededException",
      response.body(),
    )
  }

  @Test
  fun `TOMCAT-05 an ordinary multipart body with two fields and a file is parsed unchanged`() {
    val body =
      "--$BOUNDARY\r\n" +
        "Content-Disposition: form-data; name=\"first_name\"\r\n\r\nJohn\r\n" +
        "--$BOUNDARY\r\n" +
        "Content-Disposition: form-data; name=\"last_name\"\r\n\r\nDoe\r\n" +
        "--$BOUNDARY\r\n" +
        "Content-Disposition: form-data; name=\"photo_id_front\"; filename=\"id.png\"\r\n" +
        "Content-Type: image/png\r\n\r\nPNGDATA\r\n" +
        "--$BOUNDARY--\r\n"

    val response = post(body)

    assertEquals(200, response.statusCode(), response.body())
    assertEquals(
      "PARTS:first_name=John;last_name=Doe;photo_id_front=PNGDATA",
      response.body(),
    )
  }
}
