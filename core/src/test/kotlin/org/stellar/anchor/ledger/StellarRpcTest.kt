package org.stellar.anchor.ledger

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.config.AppConfig
import org.stellar.anchor.util.GsonUtils
import org.stellar.sdk.MemoText
import org.stellar.sdk.SorobanServer
import org.stellar.sdk.Transaction
import org.stellar.sdk.responses.sorobanrpc.GetLedgerEntriesResponse
import org.stellar.sdk.responses.sorobanrpc.GetTransactionResponse
import org.stellar.sdk.responses.sorobanrpc.SendTransactionResponse

class StellarRpcTest {

  private val TEST_STELLAR_RPC_URI = "https://horizon-testnet.stellar.org"
  private val TEST_HORIZON_PASSPHRASE = "Test SDF Network ; September 2015"

  private val gson = GsonUtils.getInstance()
  private lateinit var stellarRpc: StellarRpc
  private lateinit var sorobanServer: SorobanServer
  val appConfig = mockk<AppConfig>()

  @BeforeEach
  fun setUp() {
    every { appConfig.rpcUrl } returns TEST_STELLAR_RPC_URI
    every { appConfig.stellarNetworkPassphrase } returns TEST_HORIZON_PASSPHRASE

    sorobanServer = mockk<SorobanServer>()
    stellarRpc = StellarRpc(appConfig)
    stellarRpc.sorobanServer = sorobanServer
  }

  @Test
  fun `test hasTrustline() is true`() {
    every { sorobanServer.getLedgerEntries(any()) } returns
      gson.fromJson(trustlineTestResponse, GetLedgerEntriesResponse::class.java)
    val result =
      stellarRpc.hasTrustline(
        "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
        "USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
      )
    assertTrue(result)
    verify(exactly = 1) { sorobanServer.getLedgerEntries(any()) }
  }

  @Test
  fun `test hasTrustline() is false`() {
    val emptyResponse = mockk<GetLedgerEntriesResponse>()
    every { emptyResponse.entries } returns emptyList()
    every { sorobanServer.getLedgerEntries(any()) } returns emptyResponse

    val result =
      stellarRpc.hasTrustline(
        "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
        "USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
      )

    assertFalse(result)
    verify(exactly = 1) { sorobanServer.getLedgerEntries(any()) }
  }

  @Test
  fun `test getAccount()`() {
    every { sorobanServer.getLedgerEntries(any()) } returns
      gson.fromJson(accountTestResponse, GetLedgerEntriesResponse::class.java)

    val result = stellarRpc.getAccount("GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG")

    assertEquals(result.accountId, "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG")
    assertEquals(result.thresholds.getLow(), 0)
    assertEquals(result.thresholds.getMedium(), 0)
    assertEquals(result.thresholds.getHigh(), 0)
    assertEquals(result.signers[0].key, "GATEYCIMJZ2F6Y437QSYH4XFQ6HLD5YP4MBJZFFPZVEQDJOY4QTCB7BB")
    assertEquals(result.signers[1].key, "GC6X2ANA2OS3O2ESHUV6X44NH6J46EP2EO2JB7563Y7DYOIXFKHMHJ5O")
    assertEquals(result.signers[2].key, "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG")

    //    result.accountId =
    verify(exactly = 1) { sorobanServer.getLedgerEntries(any()) }
  }

  @Test
  fun `test getTransaction()`() {
    every { sorobanServer.getTransaction(any()) } returns
      gson.fromJson(txnTestResponse, GetTransactionResponse::class.java)

    val result =
      stellarRpc.getTransaction("4f7bd0fd0ec58b4d4ec31b4e37d21d4de4cbc2bd548d95d27fece550e98754c5")

    assertEquals("4f7bd0fd0ec58b4d4ec31b4e37d21d4de4cbc2bd548d95d27fece550e98754c5", result.hash)
    assertEquals("GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP", result.sourceAccount)
    assertEquals("Hello Stellar!", (result.memo as MemoText).text)
    assertEquals(8632884266970, result.sequenceNumber)

    verify(exactly = 1) { sorobanServer.getTransaction(any()) }
  }

  @Test
  fun `test getTransaction() not found`() {
    every { sorobanServer.getTransaction(any()) } returns
      gson.fromJson(txnNotFoundResponse, GetTransactionResponse::class.java)

    val result =
      stellarRpc.getTransaction("4f7bd0fd0ec58b4d4ec31b4e37d21d4de4cbc2bd548d95d27fece550e98754c5")

    assertNull(result)
  }

  @Test
  fun `test submitTransaction()`() {
    val successTxn = gson.fromJson(txnTestResponse, GetTransactionResponse::class.java)
    val notFoundTxn = gson.fromJson(txnNotFoundResponse, GetTransactionResponse::class.java)
    val spyStellarRpc = spyk(stellarRpc)
    every { sorobanServer.sendTransaction(any()) } returns
      gson.fromJson(submitTxnTestResponse, SendTransactionResponse::class.java)
    every { sorobanServer.getTransaction(any()) } returnsMany
      listOf(notFoundTxn, notFoundTxn, successTxn)
    every { spyStellarRpc.delay() } answers {}

    val result = spyStellarRpc.submitTransaction(mockk<Transaction>())

    verify(exactly = 3) { sorobanServer.getTransaction(any()) }
    assertNotNull(result)
    assertEquals("b3a5deea298f0754da4591525aa36ab824e2b5a57da18160b5da95fb35b4b6d3", result.hash)
    assertNotNull(result.envelopXdr)
    assertEquals("GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP", result.sourceAccount)
  }

  @Test
  fun `test submitTransaction() timeout`() {
    val notFoundTxn = gson.fromJson(txnNotFoundResponse, GetTransactionResponse::class.java)
    val spyStellarRpc = spyk(stellarRpc)
    every { sorobanServer.sendTransaction(any()) } returns
      gson.fromJson(submitTxnTestResponse, SendTransactionResponse::class.java)
    every { sorobanServer.getTransaction(any()) } returns notFoundTxn
    every { spyStellarRpc.delay() } answers {}

    val result = spyStellarRpc.submitTransaction(mockk<Transaction>())

    verify(exactly = stellarRpc.maxPollCount) { sorobanServer.getTransaction(any()) }
    assertNull(result)
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

private val submitTxnTestResponse =
  """
  {
    "status": "PENDING",
    "hash": "b3a5deea298f0754da4591525aa36ab824e2b5a57da18160b5da95fb35b4b6d3",
    "latestLedger": 1708794,
    "latestLedgerCloseTime": 1742404278
  }
"""
    .trimIndent()
