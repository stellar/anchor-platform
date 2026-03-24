package org.stellar.anchor.platform.service

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.stellar.anchor.LockAndMockStatic
import org.stellar.anchor.LockAndMockTest
import org.stellar.anchor.api.exception.BadRequestException
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.rpc.RpcRequest
import org.stellar.anchor.api.rpc.method.NotifyInteractiveFlowCompletedRequest
import org.stellar.anchor.api.rpc.method.RpcMethod.NOTIFY_INTERACTIVE_FLOW_COMPLETED
import org.stellar.anchor.platform.config.RpcConfig
import org.stellar.anchor.platform.rpc.RpcMethodHandler
import org.stellar.anchor.platform.utils.RpcUtil
import org.stellar.anchor.platform.utils.RpcUtil.JSON_RPC_VERSION
import org.stellar.anchor.util.GsonUtils

@ExtendWith(LockAndMockTest::class)
class RpcServiceTest {
  companion object {
    private const val RPC_ID = 1
    private const val RPC_PARAMS = "testParams"
    private const val ERROR_MSG = "Error message"
    private const val VALID_RPC_METHOD_1 = "notify_interactive_flow_completed"
    private const val VALID_RPC_METHOD_2 = "request_offchain_funds"
    private const val INVALID_RPC_METHOD = "invalid_rpc_method"
    private const val INVALID_RPC_PROTOCOL = "invalid_rpc_protocol"
    private const val BATCH_SIZE_LIMIT = 2
  }

  @MockK(relaxed = true)
  private lateinit var rpcMethodHandler: RpcMethodHandler<NotifyInteractiveFlowCompletedRequest>
  @MockK(relaxed = true) private lateinit var rpcConfig: RpcConfig

  private lateinit var rpcService: RpcService

  private val gson = GsonUtils.getInstance()

  private val rpcResponse =
    GetTransactionResponse.builder().id("testId").message("testMessage").build()

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { rpcMethodHandler.rpcMethod } returns NOTIFY_INTERACTIVE_FLOW_COMPLETED
    rpcService = RpcService(listOf(rpcMethodHandler), rpcConfig)
  }

  @Test
  fun `test handle list of valid rpc calls`() {
    val rpcRequest =
      RpcRequest.builder()
        .method(VALID_RPC_METHOD_1)
        .jsonrpc(JSON_RPC_VERSION)
        .id(RPC_ID)
        .params(RPC_PARAMS)
        .build()

    every { rpcMethodHandler.handle(any()) } returns rpcResponse
    every { rpcConfig.batchSizeLimit } returns BATCH_SIZE_LIMIT

    val response = rpcService.handle(listOf(rpcRequest, rpcRequest))

    assertEquals(2, response.size)
    assertNull(response[0].error)
    assertNull(response[1].error)

    val expectedResponse =
      """
    [
      {
        "jsonrpc": "2.0",
        "result": {
          "id": "testId",
          "message": "testMessage"
        },
        "id": 1
      },
      {
        "jsonrpc": "2.0",
        "result": {
          "id": "testId",
          "message": "testMessage"
        },
        "id": 1
      }
    ]
    """
        .trimIndent()

    JSONAssert.assertEquals(expectedResponse, gson.toJson(response), JSONCompareMode.STRICT)

    verify(exactly = 2) { rpcMethodHandler.handle(RPC_PARAMS) }
  }

  @Test
  fun `test handle invalid rpc protocol`() {
    val invalidRpcRequest =
      RpcRequest.builder()
        .method(VALID_RPC_METHOD_1)
        .jsonrpc(INVALID_RPC_PROTOCOL)
        .id(RPC_ID)
        .build()

    every { rpcConfig.batchSizeLimit } returns BATCH_SIZE_LIMIT

    val response = rpcService.handle(listOf(invalidRpcRequest))

    val expectedResponse =
      """
    [
      {
        "jsonrpc": "2.0",
        "error": {
          "code": -32600,
          "message": "Unsupported JSON-RPC protocol version[invalid_rpc_protocol]"
        },
        "id": 1
      }
    ]
    """
        .trimIndent()

    JSONAssert.assertEquals(expectedResponse, gson.toJson(response), JSONCompareMode.STRICT)

    verify(exactly = 0) { rpcMethodHandler.handle(any()) }
  }

  @Test
  fun `test handle invalid rpc call`() {
    val invalidRpcRequest = RpcRequest.builder().method(INVALID_RPC_METHOD).id(RPC_ID).build()

    every { rpcConfig.batchSizeLimit } returns BATCH_SIZE_LIMIT

    val response = rpcService.handle(listOf(invalidRpcRequest))

    val expectedResponse =
      """
    [
      {
        "jsonrpc": "2.0",
        "error": {
          "code": -32600,
          "message": "Unsupported JSON-RPC protocol version[null]"
        },
        "id": 1
      }
    ]
    """
        .trimIndent()

    JSONAssert.assertEquals(expectedResponse, gson.toJson(response), JSONCompareMode.STRICT)

    verify(exactly = 0) { rpcMethodHandler.handle(any()) }
  }

  @Test
  fun `test handle invalid rpc method`() {
    val invalidRpcRequest =
      RpcRequest.builder()
        .method(INVALID_RPC_METHOD)
        .jsonrpc(JSON_RPC_VERSION)
        .id(RPC_ID)
        .params(RPC_PARAMS)
        .build()

    every { rpcConfig.batchSizeLimit } returns BATCH_SIZE_LIMIT

    val response = rpcService.handle(listOf(invalidRpcRequest))

    val expectedResponse =
      """
    [
      {
        "jsonrpc": "2.0",
        "error": {
          "code": -32601,
          "message": "No matching RPC method[invalid_rpc_method]"
        },
        "id": 1
      }
    ]
    """
        .trimIndent()

    JSONAssert.assertEquals(expectedResponse, gson.toJson(response), JSONCompareMode.STRICT)

    verify(exactly = 0) { rpcMethodHandler.handle(any()) }
  }

  @Test
  fun `test handle rpc method without handler`() {
    val invalidRpcRequest =
      RpcRequest.builder()
        .method(VALID_RPC_METHOD_2)
        .jsonrpc(JSON_RPC_VERSION)
        .id(RPC_ID)
        .params(RPC_PARAMS)
        .build()

    every { rpcConfig.batchSizeLimit } returns BATCH_SIZE_LIMIT

    val response = rpcService.handle(listOf(invalidRpcRequest))

    val expectedResponse =
      """
    [
      {
        "jsonrpc": "2.0",
        "error": {
          "code": -32601,
          "message": "RPC method[request_offchain_funds] handler is not found"
        },
        "id": 1
      }
    ]
    """
        .trimIndent()

    JSONAssert.assertEquals(expectedResponse, gson.toJson(response), JSONCompareMode.STRICT)

    verify(exactly = 0) { rpcMethodHandler.handle(any()) }
  }

  @Test
  fun `test handle list of valid and invalid rpc calls`() {
    val rpcRequest =
      RpcRequest.builder()
        .method(VALID_RPC_METHOD_1)
        .jsonrpc(JSON_RPC_VERSION)
        .id(RPC_ID)
        .params(RPC_PARAMS)
        .build()
    val invalidRpcRequest =
      RpcRequest.builder()
        .method(VALID_RPC_METHOD_1)
        .jsonrpc(INVALID_RPC_PROTOCOL)
        .id(RPC_ID)
        .build()

    every { rpcConfig.batchSizeLimit } returns BATCH_SIZE_LIMIT

    every { rpcMethodHandler.handle(any()) } returns rpcResponse

    val response = rpcService.handle(listOf(rpcRequest, invalidRpcRequest))

    val expectedResponse =
      """
    [
      {
        "jsonrpc": "2.0",
        "result": {
          "id": "testId",
          "message": "testMessage"
        },
        "id": 1
      },
      {
        "jsonrpc": "2.0",
        "error": {
          "code": -32600,
          "message": "Unsupported JSON-RPC protocol version[invalid_rpc_protocol]"
        },
        "id": 1
      }
    ]
    """
        .trimIndent()

    JSONAssert.assertEquals(expectedResponse, gson.toJson(response), JSONCompareMode.STRICT)

    verify(exactly = 1) { rpcMethodHandler.handle(RPC_PARAMS) }
    verify(exactly = 1) { rpcMethodHandler.handle(any()) }
  }

  @Test
  @LockAndMockStatic([RpcUtil::class])
  fun `test handle internal exception`() {
    // Given
    val rpcRequest = RpcRequest.builder().build()

    every { rpcConfig.batchSizeLimit } returns BATCH_SIZE_LIMIT
    every { RpcUtil.validateRpcRequest(rpcRequest) } throws NullPointerException(ERROR_MSG)

    // When
    val response = rpcService.handle(listOf(rpcRequest))

    val expectedResponse =
      """
    [
      {
        "jsonrpc": "2.0",
        "error": {
          "code": -32603,
          "message": "Error message"
        }
      }
    ]
    """
        .trimIndent()

    // Then
    JSONAssert.assertEquals(expectedResponse, gson.toJson(response), JSONCompareMode.STRICT)

    verify(exactly = 0) { rpcMethodHandler.handle(any()) }
  }

  @Test
  @LockAndMockStatic([RpcUtil::class])
  fun `test handle bad request exception`() {
    val rpcRequest = RpcRequest.builder().build()

    every { rpcConfig.batchSizeLimit } returns BATCH_SIZE_LIMIT

    every { RpcUtil.validateRpcRequest(rpcRequest) } throws BadRequestException(ERROR_MSG)

    val response = rpcService.handle(listOf(rpcRequest))

    val expectedResponse =
      """
    [
      {
        "jsonrpc": "2.0",
        "error": {
          "code": -32602,
          "message": "Error message"
        }
      }
    ]
    """
        .trimIndent()

    JSONAssert.assertEquals(expectedResponse, gson.toJson(response), JSONCompareMode.STRICT)

    verify(exactly = 0) { rpcMethodHandler.handle(any()) }
  }

  @Test
  fun `test handle batch limit size exceeded`() {
    val rpcRequest =
      RpcRequest.builder()
        .method(VALID_RPC_METHOD_1)
        .jsonrpc(JSON_RPC_VERSION)
        .id(RPC_ID)
        .params(RPC_PARAMS)
        .build()

    every { rpcConfig.batchSizeLimit } returns BATCH_SIZE_LIMIT

    val response = rpcService.handle(listOf(rpcRequest, rpcRequest, rpcRequest))

    val expectedResponse =
      """
    [
      {
        "jsonrpc": "2.0",
        "error": {
          "code": -32600,
          "message": "RPC batch size limit[2] exceeded"
        }
      }
    ]
    """
        .trimIndent()

    JSONAssert.assertEquals(expectedResponse, gson.toJson(response), JSONCompareMode.STRICT)

    verify(exactly = 0) { rpcMethodHandler.handle(any()) }
  }
}
