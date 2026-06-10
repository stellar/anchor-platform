package org.stellar.anchor.platform.event;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.stellar.anchor.api.event.AnchorEvent;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.util.Log;

/**
 * A decorator for {@link EventService.Session} that defers event publishing until after the current
 * database transaction commits. This prevents consumers from receiving events before the
 * transaction data is visible in the database.
 *
 * <p>If no transaction is active, the event is published immediately.
 */
public class AfterCommitEventSession implements EventService.Session {
  private final EventService.Session delegate;

  public AfterCommitEventSession(EventService.Session delegate) {
    this.delegate = delegate;
  }

  @Override
  public void publish(AnchorEvent event) throws AnchorException {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              try {
                delegate.publish(event);
              } catch (AnchorException e) {
                Log.errorEx(e);
              }
            }
          });
    } else {
      delegate.publish(event);
    }
  }

  @Override
  public EventService.ReadResponse read() throws AnchorException {
    return delegate.read();
  }

  @Override
  public void ack(EventService.ReadResponse readResponse) throws AnchorException {
    delegate.ack(readResponse);
  }

  @Override
  public void close() throws AnchorException {
    delegate.close();
  }

  @Override
  public String getSessionName() {
    return delegate.getSessionName();
  }
}
