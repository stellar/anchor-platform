package org.stellar.anchor.platform

import org.junit.jupiter.api.Assertions

lateinit var referenceServerClient: ReferenceServerClient

fun referenceServerTestAll() {
  referenceServerClient = ReferenceServerClient("http://localhost:8081")
  println("Performing Reference Server tests...")
  testReferenceHealth()
}

fun testReferenceHealth() {
  val response = referenceServerClient.health(listOf("all"))

  Assertions.assertEquals(response["number_of_checks"], 1.0)
  Assertions.assertNotNull(response["checks"])
  val checks = response["checks"] as Map<*, *>
  if (checks["kafka_listener"] != null) {
    val kafkaListenerCheck = checks["kafka_listener"] as Map<*, *>
    Assertions.assertEquals(kafkaListenerCheck["status"], "green")
    Assertions.assertEquals(kafkaListenerCheck["running"], true)
    Assertions.assertEquals(kafkaListenerCheck["kafka_available"], true)
  }
}
