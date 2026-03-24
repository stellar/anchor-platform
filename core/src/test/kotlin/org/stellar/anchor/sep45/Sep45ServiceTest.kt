package org.stellar.anchor.sep45

import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.exception.BadRequestException
import org.stellar.anchor.api.exception.InternalServerErrorException
import org.stellar.anchor.api.sep.sep45.ChallengeRequest
import org.stellar.anchor.api.sep.sep45.ValidationRequest
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.auth.Nonce
import org.stellar.anchor.auth.NonceManager
import org.stellar.anchor.auth.Sep45Jwt
import org.stellar.anchor.config.SecretConfig
import org.stellar.anchor.config.Sep45Config
import org.stellar.anchor.config.StellarNetworkConfig
import org.stellar.anchor.ledger.StellarRpc
import org.stellar.anchor.util.GsonUtils
import org.stellar.sdk.Asset
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Network
import org.stellar.sdk.SorobanServer
import org.stellar.sdk.responses.sorobanrpc.GetLatestLedgerResponse
import org.stellar.sdk.responses.sorobanrpc.GetNetworkResponse
import org.stellar.sdk.responses.sorobanrpc.SimulateTransactionResponse
import org.stellar.sdk.xdr.SorobanAuthorizationEntries

class Sep45ServiceTest {
  private lateinit var stellarNetworkConfig: StellarNetworkConfig
  private lateinit var secretConfig: SecretConfig
  private lateinit var sep45Config: Sep45Config
  private lateinit var stellarRpc: StellarRpc
  private lateinit var nonceManager: NonceManager
  private lateinit var jwtService: JwtService
  private lateinit var sep45Service: Sep45Service

  private val TEST_CONTRACT_ID = "CAYXY6QGTPOCZ676MLGT5JFESVROJ6OJF7VW3LLXMTC2RQIZTP5JYNEL"

  @BeforeEach
  fun setUp() {
    stellarNetworkConfig = mockk()
    secretConfig = mockk()
    sep45Config = mockk()
    stellarRpc = mockk()
    nonceManager = mockk()
    jwtService = mockk()
    sep45Service =
      Sep45Service(
        stellarNetworkConfig,
        secretConfig,
        sep45Config,
        stellarRpc,
        nonceManager,
        jwtService,
      )
    val signingKp =
      KeyPair.fromSecretSeed("SAX3AH622R2XT6DXWWSRIDCMMUCCMATBZ5U6XKJWDO7M2EJUBFC3AW5X")
    val signingSeed = String(signingKp.secretSeed)
    val passphrase = Network.TESTNET.networkPassphrase
    val resultMock = mockk<SimulateTransactionResponse.SimulateHostFunctionResult>()
    val sorobanServer = mockk<SorobanServer>()
    val nonce = mockk<Nonce>()

    every { secretConfig.sep10SigningSeed } returns signingSeed
    every { sep45Config.webAuthContractId } returns
      Asset.createNativeAsset().getContractId(Network.TESTNET)
    every { sep45Config.webAuthDomain } returns "localhost:8080"
    every { sep45Config.homeDomains } returns listOf("http://localhost:8080")
    every { sep45Config.authTimeout } returns 300
    every { sep45Config.jwtTimeout } returns 300
    every { sep45Config.webAuthContractId } returns TEST_CONTRACT_ID

    every { nonceManager.create(300) } returns nonce
    every { nonceManager.verify(any()) } returns true
    every { nonceManager.use(any()) } answers {}
    every { nonce.id } returns "nonce-id"
    every { stellarNetworkConfig.stellarNetworkPassphrase } returns passphrase
    every { stellarRpc.latestLedger } returns GetLatestLedgerResponse("id", 23, 123455)
    every { stellarRpc.simulateTransaction(any()) } returns
      GsonUtils.getInstance()
        .fromJson(jsonSimulateTransactionResponse, SimulateTransactionResponse::class.java)
    every { resultMock.getAuth() } returns emptyList()
    every { stellarRpc.sorobanServer } returns sorobanServer
    every { sorobanServer.network } returns GetNetworkResponse(null, passphrase, 23)
  }

  @Test
  fun `test getChallenge() returns correct response or throws exception`() {
    val challengeRequest =
      ChallengeRequest.builder()
        .account(TEST_CONTRACT_ID)
        .homeDomain("http://localhost:8080")
        .clientDomain(null)
        .build()

    val response = sep45Service.getChallenge(challengeRequest)
    assertNotNull(response)
    assertEquals(Network.TESTNET.networkPassphrase, response.networkPassphrase)
  }

  @Test
  fun `test getChallenge() when simulate transaction throws exception`() {
    val challengeRequest =
      ChallengeRequest.builder()
        .account(TEST_CONTRACT_ID)
        .homeDomain("http://localhost:8080")
        .clientDomain(null)
        .build()
    val badSimulateResponse = mockk<SimulateTransactionResponse>()
    every { stellarRpc.simulateTransaction(any()) } returns badSimulateResponse
    every { badSimulateResponse.error } returns "some error"

    assertThrows(InternalServerErrorException::class.java) {
      sep45Service.getChallenge(challengeRequest)
    }
  }

  @Test
  fun `test getChallenge throws BadRequestException when account missing`() {
    val challengeRequest =
      ChallengeRequest.builder()
        .account(null)
        .homeDomain("http://localhost:8080")
        .clientDomain(null)
        .build()

    val ex =
      assertThrows(BadRequestException::class.java) { sep45Service.getChallenge(challengeRequest) }
    assertEquals("account is required", ex.message)
  }

  @Test
  fun `test getChallenge throws BadRequestException when home domain missing`() {
    val challengeRequest =
      ChallengeRequest.builder()
        .account(TEST_CONTRACT_ID)
        .homeDomain(null)
        .clientDomain(null)
        .build()

    val ex =
      assertThrows(BadRequestException::class.java) { sep45Service.getChallenge(challengeRequest) }
    assertEquals("home_domain is required", ex.message)
  }

  @Test
  fun `test getChallenge throws BadRequestException on invalid account`() {
    val challengeRequest =
      ChallengeRequest.builder()
        .account("CCXXX")
        .homeDomain("http://localhost:8080")
        .clientDomain(null)
        .build()

    val ex =
      assertThrows(BadRequestException::class.java) { sep45Service.getChallenge(challengeRequest) }
    assertEquals("account must be a contract address", ex.message)
  }

  @Test
  fun `test validate function with valid auth entries`() {
    val authEntriesXdr =
      "AAAAAgAAAAEAAAABMXx6BpvcLPv+Ys0+pKSVYuT5yS/rba13ZMWowRmb+pxF2uWzaxsY/AAIcHUAAAAQAAAAAQAAAAEAAAARAAAAAQAAAAIAAAAPAAAACnB1YmxpY19rZXkAAAAAAA0AAAAg0rDjCmCu2tWgC4nvNxeBkA6AXR61vOlF9kmFcoEQPlUAAAAPAAAACXNpZ25hdHVyZQAAAAAAAA0AAABAo074x7qA8Iqyn/P1Ewffdh7zMeBtIHvcMhTaUyIBzPEyTx67xLr9pO2AToTSh/VHFki+g3lfEz8eZsh0w0b0BQAAAAAAAAAB9rB6Ki9HordR0vv2WusMZEtYjSf5gJC5lIzUxSZAimgAAAAPd2ViX2F1dGhfdmVyaWZ5AAAAAAEAAAARAAAAAQAAAAUAAAAPAAAAB2FjY291bnQAAAAADgAAADhDQVlYWTZRR1RQT0NaNjc2TUxHVDVKRkVTVlJPSjZPSkY3VlczTExYTVRDMlJRSVpUUDVKWU5FTAAAAA8AAAALaG9tZV9kb21haW4AAAAADgAAABVodHRwOi8vbG9jYWxob3N0OjgwODAAAAAAAAAPAAAABW5vbmNlAAAAAAAADgAAACRkOTQ2YTFiOS01MzExLTQxMDgtYmQ1MC1hM2YxZjQ4YWY4ZDYAAAAPAAAAD3dlYl9hdXRoX2RvbWFpbgAAAAAOAAAADmxvY2FsaG9zdDo4MDgwAAAAAAAPAAAAF3dlYl9hdXRoX2RvbWFpbl9hY2NvdW50AAAAAA4AAAA4R0NITEhEQk9LRzJKV01KUUJUTFNMNVhHNk5PN0VTWEkyVEFRS1pYQ1hXWEI1V0kyWDZXMjMzUFIAAAAAAAAAAQAAAAAAAAAAjrOMLlG0mzEwDNcl9ubzXfJK6NTBBWbiva4e2Rq/ra0rVNLbc72XYAAIcHUAAAAQAAAAAQAAAAEAAAARAAAAAQAAAAIAAAAPAAAACnB1YmxpY19rZXkAAAAAAA0AAAAgjrOMLlG0mzEwDNcl9ubzXfJK6NTBBWbiva4e2Rq/ra0AAAAPAAAACXNpZ25hdHVyZQAAAAAAAA0AAABAw4WS+M2bdw9HoLBOiFT9DjqU02Z8gm13Mk0/sBS2AIdC7AbxmoWtS/o1A6feb/hNixTaSBArU0SZKx/l3p5TBAAAAAAAAAAB9rB6Ki9HordR0vv2WusMZEtYjSf5gJC5lIzUxSZAimgAAAAPd2ViX2F1dGhfdmVyaWZ5AAAAAAEAAAARAAAAAQAAAAUAAAAPAAAAB2FjY291bnQAAAAADgAAADhDQVlYWTZRR1RQT0NaNjc2TUxHVDVKRkVTVlJPSjZPSkY3VlczTExYTVRDMlJRSVpUUDVKWU5FTAAAAA8AAAALaG9tZV9kb21haW4AAAAADgAAABVodHRwOi8vbG9jYWxob3N0OjgwODAAAAAAAAAPAAAABW5vbmNlAAAAAAAADgAAACRkOTQ2YTFiOS01MzExLTQxMDgtYmQ1MC1hM2YxZjQ4YWY4ZDYAAAAPAAAAD3dlYl9hdXRoX2RvbWFpbgAAAAAOAAAADmxvY2FsaG9zdDo4MDgwAAAAAAAPAAAAF3dlYl9hdXRoX2RvbWFpbl9hY2NvdW50AAAAAA4AAAA4R0NITEhEQk9LRzJKV01KUUJUTFNMNVhHNk5PN0VTWEkyVEFRS1pYQ1hXWEI1V0kyWDZXMjMzUFIAAAAA"
    val validationRequest = ValidationRequest.builder().authorizationEntries(authEntriesXdr).build()
    val jwtToken = "header.payload.signature"
    val sep45Jwt =
      Sep45Jwt(
        "http://localhost:8080",
        null,
        1625247600,
        1625247900,
        "unique-jti",
        null,
        "http://localhost:8080",
      )

    every { jwtService.decode(jwtToken, Sep45Jwt::class.java) } returns sep45Jwt
    every { jwtService.encode<Sep45Jwt>(any()) } returns jwtToken

    val response = sep45Service.validate(validationRequest)

    assertNotNull(response)
    assertEquals(jwtToken, response.token)
  }

  @Test
  fun `test validate throws BadRequestException when auth entries missing`() {
    val validationRequest = ValidationRequest.builder().authorizationEntries(null).build()

    val ex =
      assertThrows(BadRequestException::class.java) { sep45Service.validate(validationRequest) }
    assertEquals("authorization_entries is required", ex.message)
  }

  @Test
  fun `test validate throws BadRequestException when auth entries list empty`() {
    val emptyAuth = SorobanAuthorizationEntries(arrayOf())
    val validationRequest =
      ValidationRequest.builder().authorizationEntries(emptyAuth.toXdrBase64()).build()

    val ex =
      assertThrows(BadRequestException::class.java) { sep45Service.validate(validationRequest) }
    assertEquals("authorization_entries must contain at least one entry", ex.message)
  }

  val jsonSimulateTransactionResponse =
    """
      {
        "transactionData": "AAAAAQAAAAIAAAACAAAAAwAAAAMAAAAAAAAAAI6zjC5RtJsxMAzXJfbm813ySujUwQVm4r2uHtkav62tAAAABgAAAAExfHoGm9ws+/5izT6kpJVi5PnJL+ttrXdkxajBGZv6nAAAABQAAAABAAAABz0KW1j1O1KUk0124aaO3EwFuHLo0cqpMrxoHqaC+HNBAAAABAAAAAYAAAAAAAAAAI6zjC5RtJsxMAzXJfbm813ySujUwQVm4r2uHtkav62tAAAAFQ5/3Z/oSEypAAAAAAAAAAYAAAABMXx6BpvcLPv+Ys0+pKSVYuT5yS/rba13ZMWowRmb+pwAAAAVMkYHQGZZEocAAAAAAAAABgAAAAH2sHoqL0eit1HS+/Za6wxkS1iNJ/mAkLmUjNTFJkCKaAAAABQAAAABAAAAB95DfxwvuD9AyIbUwUhpSk5WDiCcTcEeOYJCwzUDQXb8ABj9igAACCgAAAgsAAAAAAAl0sI=",
        "events": [
          "AAAAAQAAAAAAAAAAAAAAAgAAAAAAAAADAAAADwAAAAdmbl9jYWxsAAAAAA0AAAAg9rB6Ki9HordR0vv2WusMZEtYjSf5gJC5lIzUxSZAimgAAAAPAAAAD3dlYl9hdXRoX3ZlcmlmeQAAAAARAAAAAQAAAAUAAAAPAAAAB2FjY291bnQAAAAADgAAADhDQVlYWTZRR1RQT0NaNjc2TUxHVDVKRkVTVlJPSjZPSkY3VlczTExYTVRDMlJRSVpUUDVKWU5FTAAAAA8AAAALaG9tZV9kb21haW4AAAAADgAAABVodHRwOi8vbG9jYWxob3N0OjgwODAAAAAAAAAPAAAABW5vbmNlAAAAAAAADgAAACQ3YmVhMTBhZi03ZTFjLTRmMGYtYTAxYy1jMzVjZjlhMDA2YjYAAAAPAAAAD3dlYl9hdXRoX2RvbWFpbgAAAAAOAAAADmxvY2FsaG9zdDo4MDgwAAAAAAAPAAAAF3dlYl9hdXRoX2RvbWFpbl9hY2NvdW50AAAAAA4AAAA4R0NITEhEQk9LRzJKV01KUUJUTFNMNVhHNk5PN0VTWEkyVEFRS1pYQ1hXWEI1V0kyWDZXMjMzUFI=",
          "AAAAAQAAAAAAAAAB9rB6Ki9HordR0vv2WusMZEtYjSf5gJC5lIzUxSZAimgAAAACAAAAAAAAAAIAAAAPAAAACWZuX3JldHVybgAAAAAAAA8AAAAPd2ViX2F1dGhfdmVyaWZ5AAAAAAE="
        ],
        "minResourceFee": 2478786,
        "results": [
          {
            "auth": [
              "AAAAAQAAAAExfHoGm9ws+/5izT6kpJVi5PnJL+ttrXdkxajBGZv6nDJGB0BmWRKHAAAAAAAAAAEAAAAAAAAAAfaweiovR6K3UdL79lrrDGRLWI0n+YCQuZSM1MUmQIpoAAAAD3dlYl9hdXRoX3ZlcmlmeQAAAAABAAAAEQAAAAEAAAAFAAAADwAAAAdhY2NvdW50AAAAAA4AAAA4Q0FZWFk2UUdUUE9DWjY3Nk1MR1Q1SkZFU1ZST0o2T0pGN1ZXM0xMWE1UQzJSUUlaVFA1SllORUwAAAAPAAAAC2hvbWVfZG9tYWluAAAAAA4AAAAVaHR0cDovL2xvY2FsaG9zdDo4MDgwAAAAAAAADwAAAAVub25jZQAAAAAAAA4AAAAkN2JlYTEwYWYtN2UxYy00ZjBmLWEwMWMtYzM1Y2Y5YTAwNmI2AAAADwAAAA93ZWJfYXV0aF9kb21haW4AAAAADgAAAA5sb2NhbGhvc3Q6ODA4MAAAAAAADwAAABd3ZWJfYXV0aF9kb21haW5fYWNjb3VudAAAAAAOAAAAOEdDSExIREJPS0cySldNSlFCVExTTDVYRzZOTzdFU1hJMlRBUUtaWENYV1hCNVdJMlg2VzIzM1BSAAAAAA==",
              "AAAAAQAAAAAAAAAAjrOMLlG0mzEwDNcl9ubzXfJK6NTBBWbiva4e2Rq/ra0Of92f6EhMqQAAAAAAAAABAAAAAAAAAAH2sHoqL0eit1HS+/Za6wxkS1iNJ/mAkLmUjNTFJkCKaAAAAA93ZWJfYXV0aF92ZXJpZnkAAAAAAQAAABEAAAABAAAABQAAAA8AAAAHYWNjb3VudAAAAAAOAAAAOENBWVhZNlFHVFBPQ1o2NzZNTEdUNUpGRVNWUk9KNk9KRjdWVzNMTFhNVEMyUlFJWlRQNUpZTkVMAAAADwAAAAtob21lX2RvbWFpbgAAAAAOAAAAFWh0dHA6Ly9sb2NhbGhvc3Q6ODA4MAAAAAAAAA8AAAAFbm9uY2UAAAAAAAAOAAAAJDdiZWExMGFmLTdlMWMtNGYwZi1hMDFjLWMzNWNmOWEwMDZiNgAAAA8AAAAPd2ViX2F1dGhfZG9tYWluAAAAAA4AAAAObG9jYWxob3N0OjgwODAAAAAAAA8AAAAXd2ViX2F1dGhfZG9tYWluX2FjY291bnQAAAAADgAAADhHQ0hMSERCT0tHMkpXTUpRQlRMU0w1WEc2Tk83RVNYSTJUQVFLWlhDWFdYQjVXSTJYNlcyMzNQUgAAAAA="
            ],
            "xdr": "AAAAAQ=="
          }
        ],
        "stateChanges": [
          {
            "type": "created",
            "key": "AAAABgAAAAAAAAAAjrOMLlG0mzEwDNcl9ubzXfJK6NTBBWbiva4e2Rq/ra0AAAAVDn/dn+hITKkAAAAA",
            "after": "AAAAAAAAAAYAAAAAAAAAAAAAAACOs4wuUbSbMTAM1yX25vNd8kro1MEFZuK9rh7ZGr+trQAAABUOf92f6EhMqQAAAAAAAAABAAAAAA=="
          },
          {
            "type": "created",
            "key": "AAAABgAAAAExfHoGm9ws+/5izT6kpJVi5PnJL+ttrXdkxajBGZv6nAAAABUyRgdAZlkShwAAAAA=",
            "after": "AAAAAAAAAAYAAAAAAAAAATF8egab3Cz7/mLNPqSklWLk+ckv622td2TFqMEZm/qcAAAAFTJGB0BmWRKHAAAAAAAAAAEAAAAA"
          },
          {
            "type": "created",
            "key": "AAAABgAAAAH2sHoqL0eit1HS+/Za6wxkS1iNJ/mAkLmUjNTFJkCKaAAAABQAAAAB",
            "after": "AAWKpAAAAAYAAAAAAAAAAfaweiovR6K3UdL79lrrDGRLWI0n+YCQuZSM1MUmQIpoAAAAFAAAAAEAAAATAAAAAN5DfxwvuD9AyIbUwUhpSk5WDiCcTcEeOYJCwzUDQXb8AAAAAQAAAAEAAAAQAAAAAQAAAAEAAAAPAAAABUFkbWluAAAAAAAAEgAAAAAAAAAAjrOMLlG0mzEwDNcl9ubzXfJK6NTBBWbiva4e2Rq/ra0AAAAA"
          },
          {
            "type": "created",
            "key": "AAAAB95DfxwvuD9AyIbUwUhpSk5WDiCcTcEeOYJCwzUDQXb8",
            "after": "AANvOAAAAAcAAAABAAAAAAAAAAAAAAFPAAAACgAAAAMAAAAAAAAABwAAAAEAAAAAAAAACwAAAAcAAAA43kN/HC+4P0DIhtTBSGlKTlYOIJxNwR45gkLDNQNBdvwAAAaAAGFzbQEAAAABJgdgAn5+AX5gAX4BfmADfn5+AX5gAAF+YAN/f38AYAAAYAJ/fwF+AkMLAXYBZwAAAWIBOAABAWwBMAAAAWwBMQAAAWEBMAABAWwBNgABAWwBXwACAW0BNAAAAW0BMQAAAWEBMQABAWIBagAAAwsKAwQBBQEBBgUFBQUDAQARBhkDfwFBgIDAAAt/AEG4gMAAC38AQcCAwAALB1UHBm1lbW9yeQIAB3VwZ3JhZGUADQ1fX2NvbnN0cnVjdG9yAA8Pd2ViX2F1dGhfdmVyaWZ5ABABXwAUCl9fZGF0YV9lbmQDAQtfX2hlYXBfYmFzZQMCCrEGCmMCAX8BfiOAgICAAEEQayIAJICAgIAAIABBgIDAgABBBRCMgICAAAJAIAAoAgBBAUcNAAALIAAgACkDCDcDACAArUIghkIEhEKEgICAEBCAgICAACEBIABBEGokgICAgAAgAQvbAQIBfgR/AkACQCACQQlLDQBCACEDIAIhBCABIQUDQAJAIAQNACADQgiGQg6EIQMMAwtBASEGAkAgBS0AACIHQd8ARg0AAkAgB0FQakH/AXFBCkkNAAJAIAdBv39qQf8BcUEaSQ0AIAdBn39qQf8BcUEZSw0EIAdBRWohBgwCCyAHQUtqIQYMAQsgB0FSaiEGCyADQgaGIAatQv8Bg4QhAyAEQX9qIQQgBUEBaiEFDAALCyABrUIghkIEhCACrUIghkIEhBCKgICAACEDCyAAQgA3AwAgACADNwMIC3kBAX4CQAJAAkAgAEL/AYNCyABSDQAgABCBgICAAEKAgICAcINCgICAgIAEUg0AEIuAgIAAIgFCAhCCgICAAEIBUg0BIAFCAhCDgICAACIBQv8Bg0LNAFENAgsACxCOgICAAAALIAEQhICAgAAaIAAQhYCAgAAaQgILCQAQk4CAgAAACyUAAkAgAEL/AYNCzQBRDQAACxCLgICAACAAQgIQhoCAgAAaQgIL7AEBAn4CQCAAQv8Bg0LMAFINAEKDgICAECEBAkAgAEGFgMCAAEEHEJGAgIAAIgIQh4CAgABCAVINACAAIAIQiICAgAAiAkL/AYNCyQBSDQEgAhCJgICAABCEgICAABogAEGMgMCAAEEXEJGAgIAAIgIQh4CAgABCAVINACAAIAIQiICAgAAiAUL/AYNCyQBSDQEgARCJgICAABCEgICAABpCAiEBIABBo4DAgABBFRCRgICAACICEIeAgIAAQgFSDQAgACACEIiAgIAAIgBC/wGDQskAUg0BIAAQiYCAgAAQhICAgAAaCyABDwsAC0UCAX8BfiOAgICAAEEQayICJICAgIAAIAIgACABEIyAgIAAAkAgAigCAEEBRw0AAAsgAikDCCEDIAJBEGokgICAgAAgAwsDAAALCQAQkoCAgAAACwIACwtBAQBBgIDAAAs4QWRtaW5hY2NvdW50d2ViX2F1dGhfZG9tYWluX2FjY291bnRjbGllbnRfZG9tYWluX2FjY291bnQAnwEOY29udHJhY3RtZXRhdjAAAAAAAAAAA3NlcAAAAAACNDUAAAAAAAAAAAAHdmVyc2lvbgAAAAAFMC4xLjAAAAAAAAAAAAAABXJzdmVyAAAAAAAABjEuODcuMAAAAAAAAAAAAAhyc3Nka3ZlcgAAAC8yMi4wLjUjMjVkYWFmMzk3OTcxZjJjMTVmZDJhNWZkMGE5OTY3MDIwYTE5Y2ZjYgAAywIOY29udHJhY3RzcGVjdjAAAAACAAAAAAAAAAAAAAAHRGF0YUtleQAAAAABAAAAAAAAAAAAAAAFQWRtaW4AAAAAAAAAAAAAAAAAAAd1cGdyYWRlAAAAAAEAAAAAAAAADW5ld193YXNtX2hhc2gAAAAAAAPuAAAAIAAAAAAAAAAEAAAAAAAAAAAAAAAMV2ViQXV0aEVycm9yAAAAAQAAAAAAAAAPTWlzc2luZ0FyZ3VtZW50AAAAAAEAAAAAAAAAAAAAAA1fX2NvbnN0cnVjdG9yAAAAAAAAAQAAAAAAAAAFYWRtaW4AAAAAAAATAAAAAAAAAAAAAAAAAAAAD3dlYl9hdXRoX3ZlcmlmeQAAAAABAAAAAAAAAARhcmdzAAAD7AAAABEAAAAQAAAAAQAAA+kAAAPtAAAAAAAAB9AAAAAMV2ViQXV0aEVycm9yAB4RY29udHJhY3RlbnZtZXRhdjAAAAAAAAAAFgAAAAAAAAAA"
          }
        ],
        "latestLedger": 552592
      }    
        """
      .trimIndent()
}
