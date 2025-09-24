package org.stellar.anchor.platform

import com.google.gson.Gson
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets.UTF_8
import org.springframework.util.FileCopyUtils
import org.stellar.anchor.platform.utils.ResourceHelper.resource
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.Log.debug
import org.stellar.anchor.util.StringHelper.json

val gson: Gson = GsonUtils.getInstance()

fun resourceAsString(path: String): String {
  val reader = InputStreamReader(resource(path).inputStream, UTF_8)
  return FileCopyUtils.copyToString(reader)
}

fun printRequest(title: String?, payload: Any? = null) {
  if (title != null) println(title)
  if (payload != null) {
    debug("request=" + if (payload is String) payload else json(payload))
  }
}

fun printResponse(payload: Any?) {
  printResponse(null, payload)
}

fun printResponse(title: String?, payload: Any?) {
  if (title != null) println(title)
  if (payload != null) {
    debug("response=" + if (payload is String) payload else json(payload))
  }
  println()
}
