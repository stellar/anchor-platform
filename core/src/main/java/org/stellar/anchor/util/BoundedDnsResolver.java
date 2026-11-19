package org.stellar.anchor.util;

import static org.stellar.anchor.util.Log.infoF;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resolves caller-controlled host names on a dedicated, bounded executor and gives up after a fixed
 * time.
 *
 * <p>{@code InetAddress.getAllByName} is a native call that ignores {@code Thread.interrupt()}, so
 * a lookup against a nameserver that never answers keeps its thread for as long as the OS resolver
 * does. Running it here, instead of on the client_domain fetch pool, keeps those threads away from
 * the fetch pool's permits: a caller waits at most the timeout and the stuck worker is held by this
 * pool alone.
 */
class BoundedDnsResolver {

  static final long DEFAULT_TIMEOUT_MS = 1_000;
  static final int DEFAULT_MAX_WORKERS = 8;
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

  private final Lookup lookup;
  private final long timeoutMs;
  private final ThreadPoolExecutor executor;

  BoundedDnsResolver(Lookup lookup) {
    this(lookup, DEFAULT_MAX_WORKERS, DEFAULT_TIMEOUT_MS);
  }

  BoundedDnsResolver(Lookup lookup, int maxWorkers, long timeoutMs) {
    this.lookup = lookup;
    this.timeoutMs = timeoutMs;
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
}
