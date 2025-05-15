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
      store.upsert(account.account, account.lastObserved);
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
      ObservingAccount existingAccount = allAccounts.get(observingAccount.account);
      if (existingAccount == null) {
        allAccounts.put(observingAccount.account, observingAccount);
        // update the database
        store.upsert(observingAccount.account, observingAccount.lastObserved);
      } else {
        existingAccount.account = observingAccount.account;
        existingAccount.lastObserved = observingAccount.lastObserved;
        if (existingAccount.type == AccountType.TRANSIENT) {
          existingAccount.type = observingAccount.type;
        }
      }
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
   * @param account The account to be checked.
   * @return true if the account is being observed. false, otherwise.
   */
  public boolean lookupAndUpdate(String account) {
    if (account == null) {
      return false;
    }

    // MuxedAccount handles both muxed and non-muxed accounts
    MuxedAccount muxedAccount = new MuxedAccount(account);
    ObservingAccount acct = allAccounts.get(muxedAccount.getAccountId());
    if (acct == null) return false;
    acct.lastObserved = Instant.now();
    return true;
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
        store.delete(acct.account);
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
