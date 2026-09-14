package org.stellar.anchor.platform.observer.stellar;

import static java.time.temporal.ChronoUnit.DAYS;
import static java.time.temporal.ChronoUnit.MINUTES;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.AllArgsConstructor;
import org.stellar.anchor.platform.data.PaymentObservingAccount;
import org.stellar.anchor.platform.utils.DaemonExecutors;
import org.stellar.anchor.util.Log;
import org.stellar.sdk.MuxedAccount;

public class PaymentObservingAccountsManager {
  final Map<String, ObservingAccount> allAccounts;
  private final PaymentObservingAccountStore store;

  public PaymentObservingAccountsManager(PaymentObservingAccountStore store) {
    this.store = store;
    allAccounts = new ConcurrentHashMap<>();
  }

  @PostConstruct
  public void initialize() {
    List<PaymentObservingAccount> accounts = store.list();
    for (PaymentObservingAccount account : accounts) {
      ObservingAccount oa =
          new ObservingAccount(
              account.getAccount(), account.getLastObserved(), AccountType.TRANSIENT);
      upsert(oa);
    }
  }

  public void start() {
    Log.debug("Start the eviction task...");
    ScheduledExecutorService scheduler = DaemonExecutors.newScheduledThreadPool(1);
    scheduler.scheduleAtFixedRate(
        this::evictAndPersist, 60, getEvictPeriod().getSeconds(), TimeUnit.SECONDS);
  }

  /**
   * The shutdown hook that is run when Spring terminates. The allAccounts map will be evicted and
   * flushed.
   */
  @PreDestroy
  public void shutdown() {
    evictAndPersist();
  }

  public void evictAndPersist() {
    Log.debug("Evicting old accounts...");
    this.evict(getEvictMaxIdleTime());
    Log.debug("Persisting accounts...");
    for (ObservingAccount account : this.getAccounts()) {
      persistUpsert(account.account, account.lastObserved);
    }
  }

  /**
   * Adds an account to be observed. If the account is being observed, it will be updated.
   *
   * @param account The account being observed.
   * @param type true The account type.
   */
  public void upsert(String account, AccountType type) {
    if (account != null && type != null) {
      upsert(new ObservingAccount(account, Instant.now(), type));
    }
  }

  /**
   * Add an account to be observed. If the account is being observed, it will be updated.
   *
   * @param observingAccount The account being observed.
   */
  public void upsert(ObservingAccount observingAccount) {
    if (observingAccount != null) {
      String canonicalAccount = safeCanonicalize(observingAccount.account);
      ObservingAccount merged =
          allAccounts.compute(
              canonicalAccount,
              (key, existing) -> {
                if (existing == null) {
                  return new ObservingAccount(
                      canonicalAccount, observingAccount.lastObserved, observingAccount.type);
                }
                Instant lastObserved =
                    observingAccount.lastObserved.isAfter(existing.lastObserved)
                        ? observingAccount.lastObserved
                        : existing.lastObserved;
                AccountType type =
                    existing.type == AccountType.TRANSIENT ? observingAccount.type : existing.type;
                return new ObservingAccount(canonicalAccount, lastObserved, type);
              });

      boolean persisted = persistUpsert(merged.account, merged.lastObserved);
      if (persisted && !canonicalAccount.equals(observingAccount.account)) {
        persistDelete(observingAccount.account);
      }
    }
  }

  private static String canonicalize(String account) {
    if (account != null && account.startsWith("M")) {
      return new MuxedAccount(account).getAccountId();
    }
    return account;
  }

  private static String safeCanonicalize(String account) {
    try {
      return canonicalize(account);
    } catch (RuntimeException ex) {
      Log.errorEx(String.format("Failed to canonicalize observing account %s", account), ex);
      return account;
    }
  }

  private boolean persistUpsert(String account, Instant lastObserved) {
    try {
      store.upsert(account, lastObserved);
      return true;
    } catch (RuntimeException ex) {
      Log.errorEx(String.format("Failed to persist observing account %s", account), ex);
      return false;
    }
  }

  private void persistDelete(String account) {
    try {
      store.delete(account);
    } catch (RuntimeException ex) {
      Log.errorEx(String.format("Failed to delete stale observing account %s", account), ex);
    }
  }

  /**
   * Gets the list of observed accounts.
   *
   * @return The list of observed accounts.
   */
  public List<ObservingAccount> getAccounts() {
    return new ArrayList<>(allAccounts.values());
  }

  /**
   * Look up if the account is being observed. If the account is being observed, the lastObserved
   * timestamp of the observing account will be updated.
   *
   * @param account The account to be checked. The account can be a muxed account or a G-account.
   * @return true if the account is being observed. false, otherwise.
   */
  public boolean lookupAndUpdate(String account) {
    if (account == null) {
      return false;
    }

    String canonicalAccount = canonicalize(account);

    ObservingAccount updated =
        allAccounts.computeIfPresent(
            canonicalAccount,
            (key, existing) ->
                new ObservingAccount(existing.account, Instant.now(), existing.type));
    return updated != null;
  }

  /**
   * Evict expired accounts
   *
   * @param maxIdleTime evict all accounts that are older than maxAge
   */
  public void evict(Duration maxIdleTime) {
    for (ObservingAccount acct : getAccounts()) {
      if (acct.type == AccountType.RESIDENTIAL) continue;

      Duration idleTime = Duration.between(Instant.now(), acct.lastObserved).abs();
      if (idleTime.compareTo(maxIdleTime) > 0) {
        allAccounts.remove(acct.account);
        persistDelete(acct.account);
      }
    }
  }

  public enum AccountType {
    TRANSIENT, // the account is transient and can be flushed out of the list.
    RESIDENTIAL // the account is residential and will stay in the list. For example, a
    // distribution account
  }

  @AllArgsConstructor
  public static class ObservingAccount {
    String account;
    Instant lastObserved;
    AccountType type;
  }

  Duration getEvictPeriod() {
    return Duration.of(5, MINUTES);
  }

  Duration getEvictMaxIdleTime() {
    return Duration.of(30, DAYS);
  }
}
