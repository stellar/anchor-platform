package org.stellar.anchor.util;

import static org.stellar.anchor.util.Log.infoF;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Resolves caller-controlled host names on a dedicated, bounded executor and gives up after a fixed
 * time.
 *
 * <p>{@code InetAddress.getAllByName} is a native call that ignores {@code Thread.interrupt()}, so
 * a lookup against a nameserver that never answers keeps its thread for as long as the OS resolver
 * does. Running it here, instead of on the client_domain fetch pool, keeps those threads away from
 * the fetch pool's permits: a caller waits at most the timeout and the stuck worker is held by this
 * pool alone.
 *
 * <p>A host name whose lookup timed out is remembered for a while and rejected immediately on
 * repeat, so asking again does not take another worker. Only timeouts are remembered: a name that
 * resolves, or fails with {@link UnknownHostException}, is looked up afresh every time, which keeps
 * the DNS-rebinding guard in {@code ClientDomainHelper} effective.
 */
class BoundedDnsResolver {

  static final long DEFAULT_TIMEOUT_MS = 1_000;
  static final int DEFAULT_MAX_WORKERS = 8;
  static final long DEFAULT_NEGATIVE_TTL_MS = 30_000;
  static final int DEFAULT_NEGATIVE_CAPACITY = 1_024;
  static final String UNAVAILABLE_MESSAGE = "client_domain resolution unavailable";

  /** The blocking lookup the resolver bounds. */
  @FunctionalInterface
  interface Lookup {
    InetAddress[] lookup(String hostname) throws UnknownHostException;
  }

  /**
   * The lookup did not produce an answer: it timed out, no worker was free, it failed unexpectedly
   * or the caller was interrupted. A subclass of {@link UnknownHostException} so it can be thrown
   * from an OkHttp {@code Dns} implementation as is.
   */
  static final class ResolutionUnavailableException extends UnknownHostException {
    ResolutionUnavailableException() {
      super(UNAVAILABLE_MESSAGE);
    }
  }

  private static class ResolverThreadFactory implements ThreadFactory {
    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public Thread newThread(Runnable r) {
      Thread thread = new Thread(r, "client-domain-dns-" + counter.getAndIncrement());
      thread.setDaemon(true);
      return thread;
    }
  }

  /** Insertion-ordered map that drops its oldest entry once it holds more than {@code capacity}. */
  private static class BoundedRecord extends LinkedHashMap<String, Long> {
    private final int capacity;

    BoundedRecord(int capacity) {
      this.capacity = capacity;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
      return size() > capacity;
    }
  }

  private final Lookup lookup;
  private final long timeoutMs;
  private final ThreadPoolExecutor executor;
  private final long negativeTtlNanos;
  private final LongSupplier nanoClock;

  /** Host name to the clock reading at which its lookup timed out. Guarded by itself. */
  private final Map<String, Long> timedOut;

  BoundedDnsResolver(Lookup lookup) {
    this(lookup, DEFAULT_MAX_WORKERS, DEFAULT_TIMEOUT_MS);
  }

  BoundedDnsResolver(Lookup lookup, int maxWorkers, long timeoutMs) {
    this(
        lookup,
        maxWorkers,
        timeoutMs,
        DEFAULT_NEGATIVE_TTL_MS,
        DEFAULT_NEGATIVE_CAPACITY,
        System::nanoTime);
  }

  BoundedDnsResolver(
      Lookup lookup,
      int maxWorkers,
      long timeoutMs,
      long negativeTtlMs,
      int negativeCapacity,
      LongSupplier nanoClock) {
    this.lookup = lookup;
    this.timeoutMs = timeoutMs;
    this.negativeTtlNanos = TimeUnit.MILLISECONDS.toNanos(negativeTtlMs);
    this.nanoClock = nanoClock;
    this.timedOut = new BoundedRecord(negativeCapacity);
    this.executor =
        new ThreadPoolExecutor(
            Math.min(4, maxWorkers),
            maxWorkers,
            60L,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new ResolverThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy());
  }

  /**
   * Resolves the host name, waiting at most the configured timeout.
   *
   * @throws ResolutionUnavailableException if the lookup timed out, no worker was free, it failed
   *     unexpectedly or the caller was interrupted
   * @throws UnknownHostException if the lookup itself reported that the name does not resolve
   */
  List<InetAddress> resolve(String hostname) throws UnknownHostException {
    if (isRecordedAsTimedOut(hostname)) {
      infoF("client_domain DNS lookup for {} rejected: it timed out recently", hostname);
      throw new ResolutionUnavailableException();
    }

    FutureTask<List<InetAddress>> task =
        new FutureTask<>(() -> Arrays.asList(lookup.lookup(hostname)));
    try {
      executor.execute(task);
    } catch (RejectedExecutionException e) {
      infoF("client_domain DNS lookup for {} rejected: resolver pool saturated", hostname);
      throw new ResolutionUnavailableException();
    }

    try {
      return task.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      infoF("client_domain DNS lookup for {} timed out after {} ms", hostname, timeoutMs);
      recordTimeout(hostname);
      throw new ResolutionUnavailableException();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof UnknownHostException) {
        throw (UnknownHostException) cause;
      }
      infoF("client_domain DNS lookup for {} failed unexpectedly", hostname);
      throw new ResolutionUnavailableException();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ResolutionUnavailableException();
    }
  }

  private boolean isRecordedAsTimedOut(String hostname) {
    synchronized (timedOut) {
      Long recordedAt = timedOut.get(hostname);
      if (recordedAt == null) {
        return false;
      }
      if (nanoClock.getAsLong() - recordedAt >= negativeTtlNanos) {
        timedOut.remove(hostname);
        return false;
      }
      return true;
    }
  }

  private void recordTimeout(String hostname) {
    synchronized (timedOut) {
      long now = nanoClock.getAsLong();
      Long recordedAt = timedOut.get(hostname);
      if (recordedAt != null && now - recordedAt < negativeTtlNanos) {
        return;
      }
      timedOut.remove(hostname);
      timedOut.put(hostname, now);
    }
  }
}
