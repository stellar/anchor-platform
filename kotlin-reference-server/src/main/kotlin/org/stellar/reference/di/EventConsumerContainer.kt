package org.stellar.reference.di

import org.stellar.reference.event.EventConsumer
import org.stellar.reference.event.processor.AnchorEventProcessor
import org.stellar.reference.event.processor.NoOpEventProcessor
import org.stellar.reference.event.processor.Sep31EventProcessor
import org.stellar.reference.event.processor.Sep6EventProcessor

object EventConsumerContainer {
  val config = ConfigContainer.getInstance().config
  private val sep6EventProcessor =
    Sep6EventProcessor(
      config,
      ServiceContainer.horizon,
      ServiceContainer.rpc,
      ServiceContainer.platform,
      ServiceContainer.paymentClient,
      ServiceContainer.customerService,
      ServiceContainer.sepHelper,
    )
  private val sep31EventProcessor = Sep31EventProcessor(config, ServiceContainer.platform)
  private val noOpEventProcessor = NoOpEventProcessor()
  private val processor =
    AnchorEventProcessor(sep6EventProcessor, sep31EventProcessor, noOpEventProcessor)
  val eventConsumer = EventConsumer(ServiceContainer.eventService.channel, processor)
}
