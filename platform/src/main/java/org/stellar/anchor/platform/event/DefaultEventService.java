package org.stellar.anchor.platform.event;

import lombok.SneakyThrows;
import org.apache.commons.lang3.NotImplementedException;
import org.stellar.anchor.config.event.EventConfig;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.platform.config.PropertyEventConfig;

public class DefaultEventService implements EventService {
  private final PropertyEventConfig eventConfig;

  public DefaultEventService(EventConfig eventConfig) {
    this.eventConfig = (PropertyEventConfig) eventConfig;
  }

  @Override
  @SneakyThrows
  public Session createSession(String sessionName, EventQueue eventQueue) {
    if (eventConfig.isEnabled()) {
      switch (eventConfig.getQueue().getType()) {
        case KAFKA:
          return new KafkaSession(eventConfig.getQueue().getKafka(), sessionName, eventQueue);
        case SQS:
          // TODO: Implement this
          throw new NotImplementedException("SQS is not implemented yet");
        case MSK:
          // TODO: Implement this
          throw new NotImplementedException("MSK is not implemented yet");
      }
      throw new RuntimeException("Unknown queue type");
    } else {
      return new NoOpSession();
    }
  }
}
