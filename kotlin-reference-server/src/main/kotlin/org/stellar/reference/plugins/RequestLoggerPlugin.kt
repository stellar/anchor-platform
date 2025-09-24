package org.stellar.reference.plugins

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.*
import org.stellar.reference.log

val RequestLoggerPlugin =
  createApplicationPlugin(name = "RequestLoggerPlugin") {
    val onCallTimeKey = AttributeKey<Long>("onCallTimeKey")
    onCall { call ->
      val onCallTime = System.currentTimeMillis()
      call.attributes.put(onCallTimeKey, onCallTime)
    }

    onCallRespond { call ->
      if (call.attributes.contains(onCallTimeKey)) {
        val onCallTime = call.attributes[onCallTimeKey]
        val onCallReceiveTime = System.currentTimeMillis()
        log.info(
          "${call.request.httpMethod.value} ${call.request.local.uri} (${onCallReceiveTime - onCallTime}ms)"
        )
      }
    }
  }
