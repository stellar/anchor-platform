package org.stellar.reference.di

import org.stellar.reference.client.PaymentClient
import org.stellar.reference.event.EventConsumer
import org.stellar.reference.event.processor.AnchorEventProcessor
import org.stellar.reference.event.processor.NoOpEventProcessor
import org.stellar.reference.event.processor.Sep31EventProcessor
import org.stellar.reference.event.processor.Sep6EventProcessor
import org.stellar.sdk.KeyPair

object EventConsumerContainer {
  val config = ConfigContainer.getInstance().config
  val paymentClient =
    PaymentClient(
      ServiceContainer.horizon,
      ServiceContainer.rpc,
      KeyPair.fromSecretSeed(config.appSettings.secret),
    )
  private val sep6EventProcessor =
    Sep6EventProcessor(
      config,
      ServiceContainer.horizon,
      ServiceContainer.rpc,
      ServiceContainer.platform,
      paymentClient,
      ServiceContainer.customerService,
      ServiceContainer.sepHelper,
    )
  private val sep31EventProcessor = Sep31EventProcessor(config, ServiceContainer.platform)
  private val noOpEventProcessor = NoOpEventProcessor()
  private val processor =
    AnchorEventProcessor(sep6EventProcessor, sep31EventProcessor, noOpEventProcessor)
  val eventConsumer = EventConsumer(ServiceContainer.eventService.channel, processor)
}
