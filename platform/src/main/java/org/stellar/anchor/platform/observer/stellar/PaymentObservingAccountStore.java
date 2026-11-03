package org.stellar.anchor.platform.observer.stellar;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.stellar.anchor.platform.data.PaymentObservingAccount;
import org.stellar.anchor.platform.data.PaymentObservingAccountRepo;
import org.stellar.anchor.util.Log;

public class PaymentObservingAccountStore {
  final PaymentObservingAccountRepo repo;

  public PaymentObservingAccountStore(PaymentObservingAccountRepo repo) {
    this.repo = repo;
  }

  List<PaymentObservingAccount> list() {
    Log.debug("Retrieving the list of observing account from the store.");
    List<PaymentObservingAccount> paymentObservingAccounts = new ArrayList<>();
    repo.findAll().forEach(paymentObservingAccounts::add);
    return paymentObservingAccounts;
  }

  PaymentObservingAccount find(String account) {
    return repo.findByAccount(account);
  }

  void upsert(String account, Instant lastObserved) {
    Log.infoF("Upserting account[{}, {}]", account, lastObserved);
    PaymentObservingAccount poa = repo.findByAccount(account);
    if (poa == null) {
      poa = new PaymentObservingAccount(account, lastObserved);
      Log.infoF("Save account[{}, {}] to data store", account, lastObserved);
      repo.save(poa);
    } else if (lastObserved.isAfter(poa.getLastObserved())) {
      // save if newer
      poa.setLastObserved(lastObserved);
      Log.infoF("Update account[{}, {}] to data store", account, lastObserved);
      repo.save(poa);
    }
  }

  void delete(String account) {
    PaymentObservingAccount poa = repo.findByAccount(account);
    if (poa != null) {
      Log.infoF("Delete account[{}]", account);
      repo.delete(poa);
    } else {
      Log.warnF("Account[{}] cannot be found for deletion.", account);
    }
  }
}
