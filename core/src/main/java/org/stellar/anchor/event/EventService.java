package org.stellar.anchor.event;

import java.util.List;
import org.stellar.anchor.api.event.AnchorEvent;
import org.stellar.anchor.api.exception.AnchorException;

/**
 * The EventService is used to publish events to the event queue and to read events from the event
 * queue.
 *
 * <p>Read event example
 *
 * <pre>
 *     EventService eventService = new EventServiceImpl();
 *     eventService.publish(new TransactionEvent(...));
 *     eventService.publish(new ControlEvent(...));
 *     ...
 *     EventService.Session session = eventService.createSession("callback-api-handler", EventService.EventQueue.TRANSACTION);
 *     EventService.ReadResponse readResponse = session.read();
 *     List&lt;AnchorEvent&gt; events = readResponse.getEvents();
 *     ...
 *     session.ack(readResponse);
 *     ...
 *     session.close();
 *     ...
 *     eventService = null;
 * </pre>
 *
 * <p>Delivery is at-least-once. If an acknowledgement fails, for example because the consumer was
 * removed from its group, the event that was being handled is read and delivered again. A receiver
 * identifies a repeat by the event {@code id}.
 *
 * <p>Publish event example
 *
 * <pre>
 *     EventService eventService = new EventServiceImpl();
 *     EventService.Session session = eventService.createSession(EventService.EventQueue.TRANSACTION);
 *     session.publish(new TransactionEvent(...));
 *     session.close();
 * </pre>
 */
public interface EventService {
  /**
   * Creates a session for publishing and reading events.
   *
   * @param name the name of the session.
   * @param eventQueue the event queue to use for the session.
   * @return a session object.
   */
  Session createSession(String name, EventQueue eventQueue);

  interface Session {
    /**
     * Publishes an event to the event queue. The queue will be determined by the implementation of
     * the Session.
     *
     * @param event the event to publish
     * @throws AnchorException if the event could not be published.
     */
    void publish(AnchorEvent event) throws AnchorException;

    /**
     * Reads events from the event queue.
     *
     * @return a ReadResponse object.
     * @throws AnchorException if the events could not be read.
     */
    ReadResponse read() throws AnchorException;

    /**
     * Acknowledges that the events returned by the read() method have been processed.
     *
     * @param readResponse the read response object.
     * @throws AnchorException if the events could not be acknowledged.
     */
    void ack(ReadResponse readResponse) throws AnchorException;

    /**
     * Acknowledges the first {@code handledCount} events returned by the read() method, so that a
     * failure later in the batch does not make the already handled events be read again.
     *
     * <p>The default does nothing until every event of the response is handled, then acknowledges
     * the whole response.
     *
     * @param readResponse the read response object.
     * @param handledCount the number of events, counted from the start of the response, that have
     *     been handled.
     * @throws AnchorException if the events could not be acknowledged.
     */
    default void ack(ReadResponse readResponse, int handledCount) throws AnchorException {
      if (handledCount >= readResponse.getEvents().size()) {
        ack(readResponse);
      }
    }

    /**
     * Tells the queue that the consumer is still alive while it handles events, without reading new
     * ones. The default does nothing.
     *
     * @throws AnchorException if the session can no longer be used and must be replaced.
     */
    default void keepAlive() throws AnchorException {}

    /**
     * Closes the session.
     *
     * @throws AnchorException if the session could not be closed.
     */
    void close() throws AnchorException;

    /**
     * Returns the name of the session.
     *
     * @return the name of the session.
     */
    String getSessionName();
  }

  interface ReadResponse {
    List<AnchorEvent> getEvents();
  }

  /** List of events queues that are supported by the EventService. */
  enum EventQueue {
    /** The event queue for the transaction events that are to be processed by the anchor. */
    TRANSACTION,

    /** The event queue for events that are used for control-plane purposes. */
    CONTROL,

    /** The event queue for testing purposes. */
    TEST
  }
}
