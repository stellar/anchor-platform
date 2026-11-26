package org.stellar.anchor.platform.controller.platform

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.stellar.anchor.api.rpc.RpcResponse
import org.stellar.anchor.platform.service.RpcService
import org.stellar.anchor.platform.utils.BoundedGsonHttpMessageConverter
import org.stellar.anchor.platform.utils.RequestBodySizeLimitFilter
import org.stellar.anchor.util.GsonUtils

/** The reporter's concurrent flood (#4242527) against the real Platform API request stack. */
class PlatformJsonFloodTest {
  @Test
  fun `16 threads posting the reporter body for 5 seconds are all refused without running out of memory`() {
    val rpcService = mockk<RpcService>()
    every { rpcService.handle(any()) } returns emptyList<RpcResponse>()
    val mvc =
      MockMvcBuilders.standaloneSetup(PlatformRpcController(rpcService))
        .addFilters<StandaloneMockMvcBuilder>(RequestBodySizeLimitFilter(1_048_576L))
        .setControllerAdvice(PlatformControllerExceptionHandler())
        .setMessageConverters(
          BoundedGsonHttpMessageConverter(
            GsonUtils.getInstance(),
            BoundedGsonHttpMessageConverter.Limits.builder()
              .maxNodes(50_000)
              .maxDepth(32)
              .rpcMaxNodes(10_000)
              .batchSizeLimit(40)
              .maxPatchRecords(1000)
              .build(),
          )
        )
        .build()
    // 330,000 empty objects in params: 990 KB, under the 1 MiB body cap; encoded once and shared
    val body =
      ("""[{"jsonrpc":"2.0","id":1,"method":"notify_x","params":[""" +
          List(330_000) { "{}" }.joinToString(",") +
          "]}]")
        .toByteArray()

    val statuses = ConcurrentLinkedQueue<Int>()
    val failures = ConcurrentLinkedQueue<Throwable>()
    val threads = 16
    val pool = Executors.newFixedThreadPool(threads)
    val start = CountDownLatch(1)
    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    repeat(threads) {
      pool.execute {
        start.await()
        while (System.nanoTime() < end) {
          try {
            val response =
              mvc
                .perform(post("/").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn()
                .response
            statuses.add(response.status)
          } catch (e: Throwable) {
            failures.add(e)
          }
        }
      }
    }
    start.countDown()
    pool.shutdown()
    assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))

    assertEquals(emptyList<Throwable>(), failures.toList())
    assertTrue(statuses.isNotEmpty(), "no request completed")
    assertEquals(setOf(400), statuses.toSet(), "every response must be 400")
    verify(exactly = 0) { rpcService.handle(any()) }
  }
}
