package org.stellar.anchor.platform.component.share;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.stellar.anchor.platform.observer.stellar.PaymentObservingAccountStore;
import org.stellar.anchor.platform.observer.stellar.PaymentObservingAccountsManager;

@Configuration
public class ObservingAccountsBeans {
  private final Environment env;

  public ObservingAccountsBeans(Environment env) {
    this.env = env;
  }

  @Bean
  public PaymentObservingAccountsManager paymentObservingAccountsManager(
      PaymentObservingAccountStore paymentObservingAccountStore) {
    PaymentObservingAccountsManager bean =
        new PaymentObservingAccountsManager(paymentObservingAccountStore);

    if (env.getProperty("sep31.enabled", Boolean.class, false)) {
      bean.start();
    }

    return bean;
  }
}
