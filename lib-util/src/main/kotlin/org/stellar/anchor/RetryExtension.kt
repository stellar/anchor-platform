package org.stellar.anchor

import java.lang.reflect.Method
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.InvocationInterceptor
import org.junit.jupiter.api.extension.ReflectiveInvocationContext

class RetryExtension : InvocationInterceptor {
  override fun interceptTestMethod(
    invocation: InvocationInterceptor.Invocation<Void>,
    invocationContext: ReflectiveInvocationContext<Method>,
    extensionContext: ExtensionContext,
  ) {
    val method = extensionContext.requiredTestMethod
    val retry =
      method.getAnnotation(Retry::class.java)
        ?: run {
          // No @Retry — run once and exit
          invocation.proceed()
          return
        }

    var last: Throwable? = null
    repeat(retry.attempts) { i ->
      try {
        invocation.proceed()
        return // success on this attempt
      } catch (t: Throwable) {
        last = t
        if (i < retry.attempts - 1 && retry.delayMs > 0) {
          Thread.sleep(retry.delayMs)
        }
      }
    }
    throw last ?: AssertionError("Test failed after ${retry.attempts} attempts")
  }
}
