@file:Suppress("unused")

package org.stellar.anchor.platform.controller

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.stellar.anchor.api.platform.HealthCheckResult
import org.stellar.anchor.api.platform.HealthCheckStatus
import org.stellar.anchor.api.platform.HealthCheckStatus.*
import org.stellar.anchor.healthcheck.HealthCheckable
import org.stellar.anchor.platform.controller.sep.SepHealthController
import org.stellar.anchor.platform.observer.stellar.AbstractPaymentObserver
import org.stellar.anchor.platform.service.HealthCheckService

class HealthControllerTest {
  @MockK private lateinit var stellarPaymentObserver: AbstractPaymentObserver

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxed = true)
  }

  @Test
  fun `health controller and stellar payment observer status code checks`() {
    every { stellarPaymentObserver.tags } returns
      listOf(HealthCheckable.Tags.ALL, HealthCheckable.Tags.EVENT)

    val healthCheckService = HealthCheckService(listOf(stellarPaymentObserver))
    val healthController = SepHealthController(healthCheckService)

    // RED should result 500
    every { stellarPaymentObserver.check() } returns PojoHealthCheckResult("observer", RED)
    var response = healthController.health(listOf("all"))
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)

    // GREEN should result 200
    every { stellarPaymentObserver.check() } returns PojoHealthCheckResult("observer", GREEN)
    response = healthController.health(listOf("all"))
    assertEquals(HttpStatus.OK, response.statusCode)

    // YELLOW should result 200
    every { stellarPaymentObserver.check() } returns PojoHealthCheckResult("observer", YELLOW)
    response = healthController.health(listOf("all"))
    assertEquals(HttpStatus.OK, response.statusCode)
  }
}

class PojoHealthCheckResult(private val name: String, private val status: HealthCheckStatus) :
  HealthCheckResult {

  override fun name(): String {
    return name
  }

  override fun getStatuses(): MutableList<HealthCheckStatus>? {
    return mutableListOf(GREEN, RED)
  }

  override fun getStatus(): HealthCheckStatus {
    return status
  }
}
