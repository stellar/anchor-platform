package org.stellar.anchor.platform.utils

import jakarta.servlet.MultipartConfigElement
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.apache.catalina.Context
import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.startup.Tomcat
import org.apache.catalina.valves.ValveBase
import org.apache.coyote.http11.Http11NioProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The reporter's run, scaled down: six connections drip one byte per second into a server with four
 * workers and a two second deadline. The probe asks `/health` five seconds after the drips began.
 * Each arm has a sensor twin without the valve, which must time out.
 */
class RequestBodyReadDeadlineValveIntegrationTest {
  private val deadlineSeconds = 2L

  /** Records the class of the coyote input buffer on the way in and out of the valves after it. */
  private class SpyValve(private val phase: String, private val seen: MutableList<String>) :
    ValveBase() {
    override fun invoke(request: Request, response: Response) {
      seen.add("$phase-enter ${request.requestURI} ${request.coyoteRequest.inputBuffer.javaClass}")
      next.invoke(request, response)
      seen.add("$phase-exit ${request.requestURI} ${request.coyoteRequest.inputBuffer.javaClass}")
    }
  }

  /** Fails the request on `/throw` before any servlet runs. */
  private class ThrowingValve : ValveBase() {
    override fun invoke(request: Request, response: Response) {
      if (request.requestURI == "/throw") throw ServletException("handler failure")
      next.invoke(request, response)
    }
  }

  private class TestServer(withValve: Boolean, deadlineSeconds: Long) : AutoCloseable {
    private val tomcat = Tomcat()
    val port: Int
    val seen = CopyOnWriteArrayList<String>()

    init {
      val baseDir = Files.createTempDirectory("deadline-valve-test").toFile()
      tomcat.setBaseDir(baseDir.absolutePath)
      tomcat.setPort(0)
      (tomcat.connector.protocolHandler as Http11NioProtocol).maxThreads = 4
      val context: Context = tomcat.addContext("", baseDir.absolutePath)
      context.pipeline.addValve(SpyValve("outer", seen))
      if (withValve) context.pipeline.addValve(RequestBodyReadDeadlineValve(deadlineSeconds))
      context.pipeline.addValve(SpyValve("inner", seen))
      context.pipeline.addValve(ThrowingValve())

      servlet(context, "json", "/json") { req, resp ->
        val body = req.inputStream.readAllBytes()
        resp.writer.print("json:${body.size}")
      }
      context.addServletMappingDecoded("/throw", "json")
      servlet(context, "form", "/form") { req, resp ->
        resp.writer.print("form:${req.getParameter("transaction")}")
      }
      servlet(context, "multipart", "/multipart", multipart = true) { req, resp ->
        resp.writer.print("multipart:${req.parts.size}")
      }
      servlet(context, "reject", "/reject") { _, resp -> resp.status = 403 }
      val health =
        Tomcat.addServlet(
          context,
          "health",
          object : HttpServlet() {
            override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
              resp.writer.print("ok")
            }
          },
        )
      context.addServletMappingDecoded("/health", health.name)

      tomcat.start()
      port = tomcat.connector.localPort
    }

    private fun servlet(
      context: Context,
      name: String,
      path: String,
      multipart: Boolean = false,
      handler: (HttpServletRequest, HttpServletResponse) -> Unit,
    ) {
      val wrapper =
        Tomcat.addServlet(
          context,
          name,
          object : HttpServlet() {
            override fun doPost(req: HttpServletRequest, resp: HttpServletResponse) =
              handler(req, resp)
          },
        )
      if (multipart) wrapper.multipartConfigElement = MultipartConfigElement("")
      context.addServletMappingDecoded(path, name)
    }

    override fun close() {
      tomcat.stop()
      tomcat.destroy()
    }
  }

  /** Connections that declare a 5000 byte body and send one byte per second. */
  private class Drip(
    private val port: Int,
    private val path: String,
    private val contentType: String,
    private val prefix: String,
    private val count: Int = 6,
    private val chunked: Boolean = false,
  ) : AutoCloseable {
    val sockets = mutableListOf<Socket>()
    private val executor = Executors.newSingleThreadScheduledExecutor()

    fun start(): Drip {
      repeat(count) {
        val socket = Socket("127.0.0.1", port)
        val head =
          "POST $path HTTP/1.1\r\nHost: x\r\nContent-Type: $contentType\r\n" +
            (if (chunked) "Transfer-Encoding: chunked" else "Content-Length: 5000") +
            "\r\n\r\n$prefix"
        socket.getOutputStream().apply {
          write(head.toByteArray())
          flush()
        }
        sockets.add(socket)
      }
      executor.scheduleAtFixedRate(
        {
          sockets.forEach {
            try {
              it.getOutputStream().apply {
                write('b'.code)
                flush()
              }
            } catch (_: IOException) {}
          }
        },
        1,
        1,
        TimeUnit.SECONDS,
      )
      return this
    }

    override fun close() {
      executor.shutdownNow()
      sockets.forEach { runCatching { it.close() } }
    }
  }

  private fun health(port: Int, timeoutMs: Int = 3000): String =
    try {
      Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
        socket.soTimeout = timeoutMs
        socket
          .getOutputStream()
          .write("GET /health HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray())
        val line = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
        line?.split(" ")?.get(1) ?: "closed"
      }
    } catch (_: SocketTimeoutException) {
      "TIMEOUT"
    }

  private fun healthWhileDripping(
    withValve: Boolean,
    path: String,
    contentType: String,
    prefix: String,
    chunked: Boolean = false,
  ): String =
    TestServer(withValve, deadlineSeconds).use { server ->
      Drip(server.port, path, contentType, prefix, chunked = chunked).start().use {
        Thread.sleep(5000)
        health(server.port)
      }
    }

  private val jsonArm = Triple("/json", "application/json", "{")
  private val formArm = Triple("/form", "application/x-www-form-urlencoded", "transaction=")
  private val multipartArm =
    Triple(
      "/multipart",
      "multipart/form-data; boundary=XX",
      "--XX\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n",
    )
  private val rejectArm = Triple("/reject", "application/json", "{")
  private val chunkedArm = Triple("/json", "application/json", "1388\r\n{")

  @Test
  fun `a dripping JSON body read as a stream does not hold the workers`() {
    val (path, type, prefix) = jsonArm
    assertEquals("200", healthWhileDripping(true, path, type, prefix))
  }

  @Test
  fun `without the valve a dripping JSON body holds every worker`() {
    val (path, type, prefix) = jsonArm
    assertEquals("TIMEOUT", healthWhileDripping(false, path, type, prefix))
  }

  @Test
  fun `a dripping form body read as parameters does not hold the workers`() {
    val (path, type, prefix) = formArm
    assertEquals("200", healthWhileDripping(true, path, type, prefix))
  }

  @Test
  fun `without the valve a dripping form body holds every worker`() {
    val (path, type, prefix) = formArm
    assertEquals("TIMEOUT", healthWhileDripping(false, path, type, prefix))
  }

  @Test
  fun `a dripping multipart body read as parts does not hold the workers`() {
    val (path, type, prefix) = multipartArm
    assertEquals("200", healthWhileDripping(true, path, type, prefix))
  }

  @Test
  fun `without the valve a dripping multipart body holds every worker`() {
    val (path, type, prefix) = multipartArm
    assertEquals("TIMEOUT", healthWhileDripping(false, path, type, prefix))
  }

  @Test
  fun `a dripping body on a rejected request does not hold the workers`() {
    val (path, type, prefix) = rejectArm
    assertEquals("200", healthWhileDripping(true, path, type, prefix))
  }

  @Test
  fun `without the valve a dripping body on a rejected request holds every worker`() {
    val (path, type, prefix) = rejectArm
    assertEquals("TIMEOUT", healthWhileDripping(false, path, type, prefix))
  }

  @Test
  fun `a rejected request's client receives the rejection before the connection is cut`() {
    TestServer(withValve = true, deadlineSeconds).use { server ->
      val (path, type, prefix) = rejectArm
      Drip(server.port, path, type, prefix, count = 1).start().use { drip ->
        val socket = drip.sockets.first()
        socket.soTimeout = 1000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

        // The drip is still running (the deadline is 2 s): the answer must already be here.
        assertEquals("HTTP/1.1 403", reader.readLine().substringBeforeLast(" "))

        // Then the connection is closed instead of being held for the rest of the body.
        socket.soTimeout = 6000
        val closed =
          try {
            while (reader.readLine() != null) {}
            true
          } catch (_: SocketTimeoutException) {
            false
          } catch (_: IOException) {
            true
          }
        assertTrue(closed, "the connection was still open 6 s after the deadline")
      }
    }
  }

  @Test
  fun `a dripping chunked body does not hold the workers`() {
    val (path, type, prefix) = chunkedArm
    assertEquals("200", healthWhileDripping(true, path, type, prefix, chunked = true))
  }

  @Test
  fun `without the valve a dripping chunked body holds every worker`() {
    val (path, type, prefix) = chunkedArm
    assertEquals("TIMEOUT", healthWhileDripping(false, path, type, prefix, chunked = true))
  }

  @Test
  fun `a request that fails after its dripping body began does not hold the workers`() {
    val (_, type, prefix) = jsonArm
    TestServer(withValve = true, deadlineSeconds).use { server ->
      Drip(server.port, "/throw", type, prefix).start().use {
        Thread.sleep(5000)
        assertEquals("200", health(server.port))
        assertFalse(server.seen.filter { it.startsWith("outer-exit") }.any { isWrapper(it) })
      }
    }
  }

  @Test
  fun `without the valve a request that fails after its dripping body began holds every worker`() {
    val (_, type, prefix) = jsonArm
    TestServer(withValve = false, deadlineSeconds).use { server ->
      Drip(server.port, "/throw", type, prefix).start().use {
        Thread.sleep(5000)
        assertEquals("TIMEOUT", health(server.port))
      }
    }
  }

  @Test
  fun `the input buffer is the wrapper inside the valve and the original after it`() {
    TestServer(withValve = true, deadlineSeconds).use { server ->
      val response = postComplete(server.port, "/json", "application/json", "{}")
      assertEquals("json:2", response.body)

      val seen = server.seen
      assertTrue(isWrapper(seen.single { it.startsWith("inner-enter /json") }))
      val before = seen.single { it.startsWith("outer-enter /json") }.substringAfterLast(" ")
      val after = seen.single { it.startsWith("outer-exit /json") }.substringAfterLast(" ")
      assertFalse(isWrapper(after))
      assertEquals(before, after)
    }
  }

  @Test
  fun `a request without a body is passed on with the original input buffer`() {
    TestServer(withValve = true, deadlineSeconds).use { server ->
      assertEquals("200", health(server.port))

      val inner = server.seen.single { it.startsWith("inner-enter /health") }
      val outer = server.seen.single { it.startsWith("outer-enter /health") }
      assertFalse(isWrapper(inner))
      assertEquals(outer.substringAfterLast(" "), inner.substringAfterLast(" "))
    }
  }

  @Test
  fun `a rejected request whose body had fully arrived keeps its Content-Length`() {
    TestServer(withValve = true, deadlineSeconds).use { server ->
      val response = postComplete(server.port, "/reject", "application/json", "{}")

      assertEquals("HTTP/1.1 403", response.status.substringBeforeLast(" "))
      assertTrue(response.headers.any { it.startsWith("Content-Length:", ignoreCase = true) })
      assertFalse(response.headers.any { it.startsWith("Transfer-Encoding:", ignoreCase = true) })
    }
  }

  @Test
  fun `a request whose body the application read keeps its Content-Length`() {
    TestServer(withValve = true, deadlineSeconds).use { server ->
      val response = postComplete(server.port, "/json", "application/json", "{}")

      assertTrue(response.headers.any { it.startsWith("Content-Length:", ignoreCase = true) })
      assertFalse(response.headers.any { it.startsWith("Transfer-Encoding:", ignoreCase = true) })
    }
  }

  @Test
  fun `a complete request is answered and a second one on the same connection after the deadline too`() {
    TestServer(withValve = true, deadlineSeconds).use { server ->
      Socket("127.0.0.1", server.port).use { socket ->
        socket.soTimeout = 5000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val body = "transaction=abc"
        val request =
          "POST /form HTTP/1.1\r\nHost: x\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: ${body.length}\r\n\r\n$body"

        socket.getOutputStream().apply {
          write(request.toByteArray())
          flush()
        }
        assertEquals("form:abc", readResponse(reader).body)

        // More than the deadline after the first request began.
        Thread.sleep(3000)
        socket.getOutputStream().apply {
          write(request.toByteArray())
          flush()
        }
        assertEquals("form:abc", readResponse(reader).body)
      }
    }
  }

  private fun isWrapper(seenLine: String) =
    seenLine.endsWith(RequestBodyReadDeadlineValve.DeadlineInputBuffer::class.java.toString())

  private class HttpResponse(val status: String, val headers: List<String>, val body: String)

  private fun postComplete(
    port: Int,
    path: String,
    contentType: String,
    body: String,
  ): HttpResponse =
    Socket("127.0.0.1", port).use { socket ->
      socket.soTimeout = 5000
      socket
        .getOutputStream()
        .write(
          ("POST $path HTTP/1.1\r\nHost: x\r\nContent-Type: $contentType\r\n" +
              "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body")
            .toByteArray()
        )
      readResponse(BufferedReader(InputStreamReader(socket.getInputStream())))
    }

  private fun readResponse(reader: BufferedReader): HttpResponse {
    val status = reader.readLine()
    val headers = mutableListOf<String>()
    var length = 0
    while (true) {
      val header = reader.readLine()
      if (header.isNullOrEmpty()) break
      headers.add(header)
      if (header.startsWith("Content-Length:", ignoreCase = true)) {
        length = header.substringAfter(":").trim().toInt()
      }
    }
    val chars = CharArray(length)
    var read = 0
    while (read < length) read += reader.read(chars, read, length - read)
    return HttpResponse(status, headers, String(chars))
  }
}
