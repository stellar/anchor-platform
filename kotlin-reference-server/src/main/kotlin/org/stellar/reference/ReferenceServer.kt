package org.stellar.reference

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlinx.coroutines.runBlocking
import org.stellar.reference.di.ConfigContainer
import org.stellar.reference.di.EventConsumerContainer
import org.stellar.reference.di.ReferenceServerContainer
import org.stellar.reference.event.EventConsumer

val log = KotlinLogging.logger {}
lateinit var eventConsumingExecutor: ExecutorService

fun main(args: Array<String>) {
  startServer(null, args.getOrNull(0)?.toBooleanStrictOrNull() ?: true)
}

fun startServer(envMap: Map<String, String>?, wait: Boolean) {
  // read config
  ConfigContainer.init(envMap)
  eventConsumingExecutor = DaemonExecutors.newFixedThreadPool(1)
  eventConsumingExecutor.submit { runEventConsumer(EventConsumerContainer.eventConsumer) }

  // start server
  log.info { "Starting Kotlin reference server" }
  ReferenceServerContainer.startServer(wait)
}

// The Future returned by ExecutorService.submit is never read, so an exception thrown by the
// consumer would vanish silently. Log it here so a dead consumer is visible to operators.
internal fun runEventConsumer(consumer: EventConsumer, logger: KLogger = log) {
  logger.info { "Starting event consumer" }
  try {
    runBlocking { consumer.start() }
    logger.info { "Event consumer stopped" }
  } catch (t: Throwable) {
    logger.error(t) { "Event consumer stopped unexpectedly" }
    throw t
  }
}

fun stopServer() {
  log.info { "Stopping event consumer..." }
  EventConsumerContainer.eventConsumer.stop()

  log.info { "Stopping Kotlin business reference server..." }
  ReferenceServerContainer.server.stop(5000, 30000)

  eventConsumingExecutor.shutdown()
  eventConsumingExecutor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)
}

class DaemonThreadFactory : ThreadFactory {
  override fun newThread(r: Runnable): Thread {
    val thread = Executors.defaultThreadFactory().newThread(r)
    thread.setDaemon(true) // Set the thread as a daemon thread
    return thread
  }
}

class DaemonExecutors {
  companion object {
    private val daemonThreadFactory: ThreadFactory = DaemonThreadFactory()

    fun newFixedThreadPool(threadCount: Int): ExecutorService {
      return Executors.newFixedThreadPool(threadCount, daemonThreadFactory)
    }
  }
}
