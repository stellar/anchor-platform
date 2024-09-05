package org.stellar.anchor.platform.callback

import io.mockk.MockKAnnotations
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.hamcrest.CoreMatchers
import org.hamcrest.MatcherAssert
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.skyscreamer.jsonassert.JSONAssert
import org.stellar.anchor.api.callback.GetCustomerRequest
import org.stellar.anchor.api.callback.GetCustomerResponse
import org.stellar.anchor.api.callback.PutCustomerRequest
import org.stellar.anchor.api.callback.PutCustomerResponse
import org.stellar.anchor.api.exception.AnchorException
import org.stellar.anchor.api.exception.BadRequestException
import org.stellar.anchor.api.exception.ServerErrorException
import org.stellar.anchor.api.sep.sep12.Field
import org.stellar.anchor.api.sep.sep12.Sep12Status
import org.stellar.anchor.api.shared.CustomerField
import org.stellar.anchor.api.shared.ProvidedCustomerField
import org.stellar.anchor.auth.AuthHelper
import org.stellar.anchor.util.GsonUtils

class RestCustomerIntegrationTest {
  companion object {
    private const val TEST_ACCOUNT = "GBFZNZTFSI6TWLVAID7VOLCIFX2PMUOS2X7U6H4TNK4PAPSHPWMMUIZG"
  }

  private val httpClient: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.MINUTES)
      .readTimeout(10, TimeUnit.MINUTES)
      .writeTimeout(10, TimeUnit.MINUTES)
      .build()
  private val authHelper = AuthHelper.forNone()
  private val gson = GsonUtils.getInstance()
  private lateinit var mockAnchor: MockWebServer
  private lateinit var mockAnchorUrl: String
  private lateinit var customerIntegration: RestCustomerIntegration

  @BeforeEach
  fun setup() {
    // mock Anchor backend
    mockAnchor = MockWebServer()
    mockAnchor.start()
    mockAnchorUrl = mockAnchor.url("").toString()

    MockKAnnotations.init(this, relaxUnitFun = true)
    customerIntegration = RestCustomerIntegration(mockAnchorUrl, httpClient, authHelper, gson)
  }

  @Test
  fun test_getCustomer() {
    val getCustomerRequest =
      GetCustomerRequest.builder()
        .id("customer-id")
        .account("account")
        .memo("memo")
        .memoType("memoType")
        .type("sending_user")
        .lang("en")
        .build()

    mockAnchor.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody(
          """{
            "id": "customer-id",
            "status": "NEEDS_INFO",
            "message": "foo bar",
            "fields": {
              "email_address": {
                "description": "email address of the customer",
                "type": "STRING",
                "optional": false
              }
            },
            "provided_fields": {
              "last_name": {
                "description": "The customer's last name",
                "type": "STRING",
                "status": "ACCEPTED"
              }
            }
        }"""
            .trimMargin()
        )
    )

    val wantResponse = GetCustomerResponse()
    wantResponse.id = "customer-id"
    wantResponse.status = Sep12Status.NEEDS_INFO.name
    wantResponse.message = "foo bar"
    val emailField = CustomerField()
    emailField.description = "email address of the customer"
    emailField.type = Field.Type.STRING.name
    emailField.optional = false
    wantResponse.fields = mapOf("email_address" to emailField)
    val lastNameProvidedField = ProvidedCustomerField()
    lastNameProvidedField.description = "The customer's last name"
    lastNameProvidedField.type = Field.Type.STRING.name
    lastNameProvidedField.status = Sep12Status.ACCEPTED.name
    wantResponse.providedFields = mapOf("last_name" to lastNameProvidedField)

    val gotResponse = customerIntegration.getCustomer(getCustomerRequest)

    assertEquals(wantResponse, gotResponse)

    val request = mockAnchor.takeRequest()
    assertEquals("GET", request.method)
    assertEquals("application/json", request.headers["Content-Type"])
    assertNull(request.headers["Authorization"])
    val wantEndpoint =
      """/customer
        ?id=customer-id
        &account=account
        &memo=memo
        &memo_type=memoType
        &type=sending_user
        &lang=en
        """
        .replace("\n        ", "")
    MatcherAssert.assertThat(request.path, CoreMatchers.endsWith(wantEndpoint))
    assertEquals("", request.body.readUtf8())
  }

  @Test
  fun test_getCustomer_failure() {
    val getCustomerRequest = GetCustomerRequest.builder().id("customer-id").build()

    mockAnchor.enqueue(MockResponse().setResponseCode(200).setBody("{}".trimMargin()))

    var ex: AnchorException = assertThrows { customerIntegration.getCustomer(getCustomerRequest) }
    assertInstanceOf(ServerErrorException::class.java, ex)
    assertEquals("internal server error: result from Anchor backend is invalid", ex.message)

    val request = mockAnchor.takeRequest()
    assertEquals("GET", request.method)
    assertEquals("application/json", request.headers["Content-Type"])
    assertNull(request.headers["Authorization"])
    val wantEndpoint = "/customer?id=customer-id"
    MatcherAssert.assertThat(request.path, CoreMatchers.endsWith(wantEndpoint))
    assertEquals("", request.body.readUtf8())

    // invalid response status code
    mockAnchor.enqueue(
      MockResponse()
        .setResponseCode(400)
        .setBody("""{"error": "foo bar went wrong"}""".trimMargin())
    )
    ex = assertThrows { customerIntegration.getCustomer(getCustomerRequest) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("foo bar went wrong", ex.message)
  }

  @Test
  fun test_putCustomer() {
    val putCustomerRequest =
      PutCustomerRequest.builder()
        .id("customer-id")
        .account(TEST_ACCOUNT)
        .memo("memo")
        .firstName("John")
        .lastName("Doe")
        .build()

    mockAnchor.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": "customer-id"}"""))

    val wantResponse = PutCustomerResponse()
    wantResponse.id = "customer-id"

    val gotResponse = customerIntegration.putCustomer(putCustomerRequest)
    assertEquals(wantResponse, gotResponse)

    val request = mockAnchor.takeRequest()
    assertEquals("PUT", request.method)
    assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
    assertNull(request.headers["Authorization"])
    val wantEndpoint = "/customer"
    MatcherAssert.assertThat(request.path, CoreMatchers.endsWith(wantEndpoint))
    val wantBody =
      """{
      "id": "customer-id",
      "account": "$TEST_ACCOUNT",
      "memo": "memo",
      "first_name": "John",
      "last_name": "Doe"
    }"""
        .trimMargin()
    JSONAssert.assertEquals(wantBody, request.body.readUtf8(), true)
  }

  @Test
  fun test_putCustomerBinaryFields() {
    val putCustomerRequest =
      PutCustomerRequest.builder()
        .id("customer-id")
        .account(TEST_ACCOUNT)
        .memo("memo")
        .firstName("John")
        .lastName("Doe")
        .photoIdFront("value_photo_id_front".toByteArray())
        .build()

    mockAnchor.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": "customer-id"}"""))

    val wantResponse = PutCustomerResponse()
    wantResponse.id = "customer-id"

    val gotResponse = customerIntegration.putCustomer(putCustomerRequest)
    assertEquals(wantResponse, gotResponse)

    val request = mockAnchor.takeRequest()
    assertEquals("PUT", request.method)
    assertEquals("multipart/form-data", request.headers["Content-Type"]?.split(';')?.get(0))
    assertNull(request.headers["Authorization"])
    val wantEndpoint = "/customer"
    MatcherAssert.assertThat(request.path, CoreMatchers.endsWith(wantEndpoint))
  }

  @Test
  fun test_putCustomer_failure() {
    val putCustomerRequest =
      PutCustomerRequest.builder().id("customer-id").account(TEST_ACCOUNT).build()

    // invalid response status code
    mockAnchor.enqueue(
      MockResponse()
        .setResponseCode(400)
        .setBody("""{"error": "foo bar went wrong"}""".trimMargin())
    )
    val ex: AnchorException = assertThrows { customerIntegration.putCustomer(putCustomerRequest) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("foo bar went wrong", ex.message)

    val request = mockAnchor.takeRequest()
    assertEquals("PUT", request.method)
    assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
    assertNull(request.headers["Authorization"])
    val wantEndpoint = "/customer"
    MatcherAssert.assertThat(request.path, CoreMatchers.endsWith(wantEndpoint))
    val wantBody =
      """{
      "id": "customer-id",
      "account": "$TEST_ACCOUNT"
    }"""
        .trimMargin()
    JSONAssert.assertEquals(wantBody, request.body.readUtf8(), true)
  }

  @Test
  fun test_deleteCustomer() {
    mockAnchor.enqueue(MockResponse().setResponseCode(204))

    assertDoesNotThrow { customerIntegration.deleteCustomer("customer-id") }

    val request = mockAnchor.takeRequest()
    assertEquals("DELETE", request.method)
    assertEquals("application/json", request.headers["Content-Type"])
    assertNull(request.headers["Authorization"])
    val wantEndpoint = "/customer/customer-id"
    MatcherAssert.assertThat(request.path, CoreMatchers.endsWith(wantEndpoint))
    assertEquals("", request.body.readUtf8())
  }
}
