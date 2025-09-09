package org.stellar.anchor.ledger

import io.mockk.*
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.config.RpcAuthConfig
import org.stellar.anchor.config.SecretConfig
import org.stellar.anchor.config.StellarNetworkConfig
import org.stellar.anchor.util.GsonUtils
import org.stellar.sdk.SorobanServer
import org.stellar.sdk.StrKey
import org.stellar.sdk.responses.sorobanrpc.GetLedgerEntriesResponse
import org.stellar.sdk.responses.sorobanrpc.GetTransactionResponse
import org.stellar.sdk.xdr.LedgerKey
import org.stellar.sdk.xdr.OperationType

class StellarRpcTest {

  private val testStellarRpcUrl = "https://horizon-testnet.stellar.org"
  private val testNetworkPassphrase = "Test SDF Network ; September 2015"

  private val gson = GsonUtils.getInstance()
  private lateinit var stellarRpc: StellarRpc
  private lateinit var sorobanServer: SorobanServer
  val stellarNetworkConfig = mockk<StellarNetworkConfig>()

  @BeforeEach
  fun setUp() {
    every { stellarNetworkConfig.rpcUrl } returns testStellarRpcUrl
    every { stellarNetworkConfig.stellarNetworkPassphrase } returns testNetworkPassphrase

    sorobanServer = mockk<SorobanServer>()
    stellarRpc = StellarRpc(stellarNetworkConfig.rpcUrl)
    stellarRpc.sorobanServer = sorobanServer
  }

  @Test
  fun `test hasTrustline() is true`() {
    val capturedKeys = slot<Collection<LedgerKey>>()
    every { sorobanServer.getLedgerEntries(capture(capturedKeys)) } returns
      gson.fromJson(trustlineTestResponse, GetLedgerEntriesResponse::class.java)

    val result =
      stellarRpc.hasTrustline(
        "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
        "USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
      )

    assertTrue(result)
    verify(exactly = 1) { sorobanServer.getLedgerEntries(any()) }

    val keys = capturedKeys.captured
    assertEquals(1, keys.size)
    val key = keys.first()
    assertNotNull(key.trustLine)
    assertEquals(
      "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
      StrKey.encodeEd25519PublicKey(key.trustLine?.accountID?.accountID?.ed25519?.uint256),
    )
  }

  @Test
  fun `test hasTrustline() is false`() {
    val emptyResponse = mockk<GetLedgerEntriesResponse>()
    every { emptyResponse.entries } returns emptyList()
    every { sorobanServer.getLedgerEntries(any()) } returns emptyResponse

    val result =
      stellarRpc.hasTrustline(
        "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
        "USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
      )

    assertFalse(result)
    verify(exactly = 1) { sorobanServer.getLedgerEntries(any()) }
  }

  @Test
  fun `test getAccount()`() {
    val capturedKeys = slot<Collection<LedgerKey>>()
    every { sorobanServer.getLedgerEntries(capture(capturedKeys)) } returns
      gson.fromJson(accountTestResponse, GetLedgerEntriesResponse::class.java)

    val result = stellarRpc.getAccount("GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG")

    assertEquals(result.accountId, "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG")
    assertEquals(result.thresholds.getLow(), 0)
    assertEquals(result.thresholds.getMedium(), 0)
    assertEquals(result.thresholds.getHigh(), 0)
    assertEquals(result.signers[0].key, "GATEYCIMJZ2F6Y437QSYH4XFQ6HLD5YP4MBJZFFPZVEQDJOY4QTCB7BB")
    assertEquals(result.signers[1].key, "GC6X2ANA2OS3O2ESHUV6X44NH6J46EP2EO2JB7563Y7DYOIXFKHMHJ5O")
    assertEquals(result.signers[2].key, "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG")

    verify(exactly = 1) { sorobanServer.getLedgerEntries(any()) }

    val keys = capturedKeys.captured
    assertEquals(1, keys.size)
    val key = keys.first()
    assertNotNull(key.account)
    assertEquals(
      "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
      StrKey.encodeEd25519PublicKey(key.account?.accountID?.accountID?.ed25519?.uint256),
    )
  }

  @Test
  fun `test getTransaction()`() {
    every {
      sorobanServer.getTransaction(
        "4f7bd0fd0ec58b4d4ec31b4e37d21d4de4cbc2bd548d95d27fece550e98754c5"
      )
    } returns gson.fromJson(txnTestResponse, GetTransactionResponse::class.java)

    val txn =
      stellarRpc.getTransaction("4f7bd0fd0ec58b4d4ec31b4e37d21d4de4cbc2bd548d95d27fece550e98754c5")

    assertEquals("4f7bd0fd0ec58b4d4ec31b4e37d21d4de4cbc2bd548d95d27fece550e98754c5", txn.hash)
    assertEquals("GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP", txn.sourceAccount)
    assertEquals("Hello Stellar!", txn.memo.text.toString())
    assertEquals(1708638, txn.sequenceNumber)
    assertEquals(1, txn.operations.size)
    assertEquals(OperationType.PAYMENT, txn.operations[0].type)
    assertEquals("7338544330723329", txn.operations[0].paymentOperation.id)

    verify(exactly = 1) {
      sorobanServer.getTransaction(
        "4f7bd0fd0ec58b4d4ec31b4e37d21d4de4cbc2bd548d95d27fece550e98754c5"
      )
    }
  }

  @Test
  fun `test getTransaction() not found`() {
    every {
      sorobanServer.getTransaction(
        "4f7bd0fd0ec58b4d4ec31b4e37d21d4de4cbc2bd548d95d27fece550e98754c5"
      )
    } returns gson.fromJson(txnNotFoundResponse, GetTransactionResponse::class.java)

    val result =
      stellarRpc.getTransaction("4f7bd0fd0ec58b4d4ec31b4e37d21d4de4cbc2bd548d95d27fece550e98754c5")

    assertNull(result)
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "https://rpc-url",
        "https://soroban-testnet.stellar.org",
        "https://soroban-mainnet.stellar.org/1234567/"
      ]
  )
  fun `When auth is NONE, SorobanServer is created with the correct URL`(testUrl: String) {
    val config = mockk<StellarNetworkConfig>()
    val secret = mockk<SecretConfig>()
    val rpcAuth = mockk<RpcAuthConfig>()
    every { config.rpcAuth } returns rpcAuth
    every { rpcAuth.type } returns RpcAuthConfig.RpcAuthType.NONE
    every { config.rpcUrl } returns testUrl

    val slot = slot<OkHttpClient>()
    val rpc = spyk(StellarRpc(config, secret))
    every { rpc.createSorobanServerWithHttpClient(testUrl, capture(slot)) } returns sorobanServer

    val capturedKeys = slot<Collection<LedgerKey>>()
    every { sorobanServer.getLedgerEntries(capture(capturedKeys)) } returns
      gson.fromJson(trustlineTestResponse, GetLedgerEntriesResponse::class.java)

    rpc.createSimpleRpcServer(config)
    // run code that constructs SorobanServer("https://rpc-url")
    verify { rpc.createSorobanServer(testUrl) }
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "https://rpc-url",
        "https://soroban-testnet.stellar.org",
        "https://soroban-mainnet.stellar.org/1234567/"
      ]
  )
  fun `When auth is HEADER, SorobanServer is created with the correct URL and HEADER`(
    testUrl: String
  ) {
    // slots
    val slotUrl = slot<String>()
    val slotHeaderValue = slot<String>()
    val slotHeaderName = slot<String>()
    val slotOkHttpClient = slot<OkHttpClient>()

    // inputs
    val config = mockk<StellarNetworkConfig>()
    val secret = mockk<SecretConfig>()
    val rpcAuth = mockk<RpcAuthConfig>()
    val headerConfig = RpcAuthConfig.HeaderConfig("Authorization")
    val chain = mockk<Interceptor.Chain>()
    val request = mockk<Request>()

    every { config.rpcAuth } returns rpcAuth
    every { rpcAuth.type } returns RpcAuthConfig.RpcAuthType.HEADER
    every { rpcAuth.headerConfig } returns headerConfig
    every { secret.rpcAuthSecret } returns "test-token"
    every { config.rpcUrl } returns testUrl
    every { chain.request() } returns request
    every { request.newBuilder() } returns
      mockk<Request.Builder> {
        every { addHeader(capture(slotHeaderName), capture(slotHeaderValue)) } returns this
        every { build() } returns request
      }
    every { chain.proceed(any()) } returns mockk()

    // action
    val rpc = spyk(StellarRpc(config, secret))
    every {
      rpc.createSorobanServerWithHttpClient(capture(slotUrl), capture(slotOkHttpClient))
    } returns sorobanServer
    rpc.createHeaderAuthServer(config, secret)
    slotOkHttpClient.captured.interceptors[1].intercept(chain)

    // assertions
    verify { rpc.createSorobanServerWithHttpClient(testUrl, any()) }
    assertEquals("test-token", slotHeaderValue.captured)
  }
}

private val trustlineTestResponse =
  """
  {
    "entries": [
      {
        "key": "AAAAAQAAAADSsOMKYK7a1aALie83F4GQDoBdHrW86UX2SYVygRA+VQAAAAFVU0RDAAAAAODia2IsqMlWCuY6k734V/dcCafJwfI1Qq7+/0qEd68A",
        "xdr": "AAAAAQAAAADSsOMKYK7a1aALie83F4GQDoBdHrW86UX2SYVygRA+VQAAAAFVU0RDAAAAAODia2IsqMlWCuY6k734V/dcCafJwfI1Qq7+/0qEd68AAAAJEU2KQAB//////////wAAAAEAAAAA",
        "lastModifiedLedgerSeq": 1708119
      }
    ],
    "latestLedger": 1708377
  }
"""
    .trimIndent()

private val accountTestResponse =
  """
{
  "entries": [
    {
      "key": "AAAAAAAAAADSsOMKYK7a1aALie83F4GQDoBdHrW86UX2SYVygRA+VQ==",
      "xdr": "AAAAAAAAAADSsOMKYK7a1aALie83F4GQDoBdHrW86UX2SYVygRA+VQAAABhRPqm3AAAH3wAACwYAAAAEAAAAAAAAAAAAAAAAAQAAAAAAAAIAAAAAJkwJDE50X2Ob/CWD8uWHjrH3D+MCnJSvzUkBpdjkJiAAAAABAAAAAL19AaDTpbdokj0r6/ONP5PPEfojtJD/vt4+PDkXKo7DAAAAAQAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAAAAAAfPAAAAAgAAAAAAAAAAAAAAAwAAAAAAGhBQAAAAAGfa7WA=",
      "lastModifiedLedgerSeq": 1708112
    }
  ],
  "latestLedger": 1708548
}
"""
    .trimIndent()

private val txnNotFoundResponse =
  """
  {
    "status": "NOT_FOUND",
    "txHash": "4f7bd0fd0ec58b4d4ec31b4e37d21d4de4cbc2bd548d95d27fece550e98754c5",
    "latestLedger": 1708637,
    "latestLedgerCloseTime": 1742403492,
    "oldestLedger": 1587678,
    "oldestLedgerCloseTime": 1741798132,
    "applicationOrder": 0,
    "feeBump": false,
    "ledger": 0,
    "createdAt": 0
  }
"""
    .trimIndent()

private val txnTestResponse =
  """
  {
    "status": "SUCCESS",
    "txHash": "4f7bd0fd0ec58b4d4ec31b4e37d21d4de4cbc2bd548d95d27fece550e98754c5",
    "latestLedger": 1708646,
    "latestLedgerCloseTime": 1742403537,
    "oldestLedger": 1587687,
    "oldestLedgerCloseTime": 1741798177,
    "applicationOrder": 5,
    "feeBump": false,
    "envelopeXdr": "AAAAAgAAAAACJQsPAYY4MkKbJBd8Wl742m73Fb648rWVhCuwCcbDzAAAAGQAAAfaAAAH2gAAAAEAAAAAAAAAAAAAAABn2vfCAAAAAQAAAA5IZWxsbyBTdGVsbGFyIQAAAAAAAQAAAAAAAAABAAAAANKw4wpgrtrVoAuJ7zcXgZAOgF0etbzpRfZJhXKBED5VAAAAAAAAAAAAAATOAAAAAAAAAAEJxsPMAAAAQLCaLM0qbpcumir7fw4DUT9JLwH23XaViBTDAAbb6nIQGSMqqZgR1gDHQzL6Ub+IG4JXbzOT8GJVwIvpKr37QAg=",
    "resultXdr": "AAAAAAAAAGQAAAAAAAAAAQAAAAAAAAABAAAAAAAAAAA=",
    "resultMetaXdr": "AAAAAwAAAAAAAAACAAAAAwAaEl4AAAAAAAAAAAIlCw8BhjgyQpskF3xaXvjabvcVvrjytZWEK7AJxsPMAAAAFlaBw40AAAfaAAAH2QAAAAIAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAAAAAAAAAAAAAAAAAAMAAAAAABoQVwAAAABn2u2DAAAAAAAAAAEAGhJeAAAAAAAAAAACJQsPAYY4MkKbJBd8Wl742m73Fb648rWVhCuwCcbDzAAAABZWgcONAAAH2gAAB9oAAAACAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAIAAAAAAAAAAAAAAAAAAAADAAAAAAAaEl4AAAAAZ9r3qQAAAAAAAAABAAAABAAAAAMAGhJeAAAAAAAAAAACJQsPAYY4MkKbJBd8Wl742m73Fb648rWVhCuwCcbDzAAAABZWgcONAAAH2gAAB9oAAAACAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAIAAAAAAAAAAAAAAAAAAAADAAAAAAAaEl4AAAAAZ9r3qQAAAAAAAAABABoSXgAAAAAAAAAAAiULDwGGODJCmyQXfFpe+Npu9xW+uPK1lYQrsAnGw8wAAAAWVoG+vwAAB9oAAAfaAAAAAgAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAACAAAAAAAAAAAAAAAAAAAAAwAAAAAAGhJeAAAAAGfa96kAAAAAAAAAAwAaEFAAAAAAAAAAANKw4wpgrtrVoAuJ7zcXgZAOgF0etbzpRfZJhXKBED5VAAAAGFE+qbcAAAffAAALBgAAAAQAAAAAAAAAAAAAAAABAAAAAAAAAgAAAAAmTAkMTnRfY5v8JYPy5YeOsfcP4wKclK/NSQGl2OQmIAAAAAEAAAAAvX0BoNOlt2iSPSvr840/k88R+iO0kP++3j48ORcqjsMAAAABAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAACAAAAAAAAB88AAAACAAAAAAAAAAAAAAADAAAAAAAaEFAAAAAAZ9rtYAAAAAAAAAABABoSXgAAAAAAAAAA0rDjCmCu2tWgC4nvNxeBkA6AXR61vOlF9kmFcoEQPlUAAAAYUT6uhQAAB98AAAsGAAAABAAAAAAAAAAAAAAAAAEAAAAAAAACAAAAACZMCQxOdF9jm/wlg/Llh46x9w/jApyUr81JAaXY5CYgAAAAAQAAAAC9fQGg06W3aJI9K+vzjT+TzxH6I7SQ/77ePjw5FyqOwwAAAAEAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAIAAAAAAAAHzwAAAAIAAAAAAAAAAAAAAAMAAAAAABoQUAAAAABn2u1gAAAAAAAAAAAAAAAA",
    "ledger": 1708638,
    "createdAt": 1742403497
  }
"""
    .trimIndent()
