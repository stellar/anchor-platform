package org.stellar.anchor.platform.observer.stellar;

import static org.stellar.anchor.api.platform.HealthCheckStatus.GREEN;
import static org.stellar.anchor.healthcheck.HealthCheckable.Tags.ALL;
import static org.stellar.anchor.healthcheck.HealthCheckable.Tags.EVENT;
import static org.stellar.anchor.util.Log.info;

import java.util.List;
import org.stellar.anchor.api.platform.HealthCheckResult;
import org.stellar.anchor.ledger.StellarRpc;
import org.stellar.anchor.platform.config.PaymentObserverConfig.StellarPaymentObserverConfig;
import org.stellar.anchor.platform.observer.PaymentListener;
import org.stellar.sdk.SorobanServer;

public class RpcPaymentObserver extends AbstractPaymentObserver {
  final StellarRpc stellarRpc;

  RpcPaymentObserver(
      String rpcUrl,
      StellarPaymentObserverConfig config,
      List<PaymentListener> paymentListeners,
      PaymentObservingAccountsManager paymentObservingAccountsManager,
      StellarPaymentStreamerCursorStore paymentStreamerCursorStore) {
    super(config, paymentListeners, paymentObservingAccountsManager, paymentStreamerCursorStore);
    this.stellarRpc = new StellarRpc(rpcUrl);
  }

  @Override
  void startInternal() {
    info("Starting Stellar RPC payment observer");
    startMockStream();
  }

  @Override
  void shutdownInternal() {
    shutdownMockStream();
  }

  @Override
  public String getName() {
    return "rpc_payment_observer";
  }

  @Override
  public List<Tags> getTags() {
    return List.of(ALL, EVENT);
  }

  @Override
  public HealthCheckResult check() {
    // TODO: Implement health check for Stellar RPC when futurenet unified event is available
    return SPOHealthCheckResult.builder().name(getName()).status(GREEN).build();
  }

  private void startMockStream() {
    SorobanServer sorobanServer = stellarRpc.getSorobanServer();
  }

  private void shutdownMockStream() {}
}
