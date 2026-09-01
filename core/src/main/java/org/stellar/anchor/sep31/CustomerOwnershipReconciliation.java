package org.stellar.anchor.sep31;

import org.stellar.anchor.api.callback.CustomerIntegration;
import org.stellar.anchor.api.callback.GetCustomerRequest;
import org.stellar.anchor.api.callback.GetCustomerResponse;
import org.stellar.anchor.auth.WebAuthJwt;
import org.stellar.anchor.util.Log;

public final class CustomerOwnershipReconciliation {

  private CustomerOwnershipReconciliation() {}

  public static boolean tryReconcile(
      Sep31CustomerIdOwnerStore store,
      CustomerIntegration customerIntegration,
      String customerId,
      WebAuthJwt token,
      String type) {
    String clientName = token.getClientName();
    if (clientName == null) {
      return false;
    }

    GetCustomerResponse callbackCustomer;
    try {
      callbackCustomer =
          customerIntegration.getCustomer(
              GetCustomerRequest.builder()
                  .account(token.getAccount())
                  .memo(token.getOwnerMemo())
                  .memoType(token.getOwnerMemo() != null ? "id" : null)
                  .type(type)
                  .build());
    } catch (Exception e) {
      Log.warnEx(e);
      return false;
    }

    if (callbackCustomer == null || !customerId.equals(callbackCustomer.getId())) {
      return false;
    }

    return store.reconcileLegacyKey(
        customerId, clientName, token.getOwnerKey(), token.getOwnerMemo());
  }
}
