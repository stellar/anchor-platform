package org.stellar.anchor

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Retry(val attempts: Int = 3, val delayMs: Long = 0)
