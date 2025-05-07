package org.stellar.anchor.platform.component.share;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.stellar.anchor.platform.data.*;
import org.stellar.anchor.platform.observer.stellar.JdbcStellarPaymentStreamerCursorStore;
import org.stellar.anchor.platform.observer.stellar.PaymentObservingAccountStore;
import org.stellar.anchor.sep38.Sep38QuoteStore;

@Configuration
public class DataBeans {
  @Bean
  JdbcSep6TransactionStore sep6TransactionStore(JdbcSep6TransactionRepo sep6TransactionRepo) {
    return new JdbcSep6TransactionStore(sep6TransactionRepo);
  }

  @Bean
  JdbcSep24TransactionStore sep24TransactionStore(JdbcSep24TransactionRepo sep24TransactionRepo) {
    return new JdbcSep24TransactionStore(sep24TransactionRepo);
  }

  @Bean
  JdbcSep31TransactionStore sep31TransactionStore(JdbcSep31TransactionRepo txnRepo) {
    return new JdbcSep31TransactionStore(txnRepo);
  }

  @Bean
  Sep38QuoteStore sep38QuoteStore(JdbcSep38QuoteRepo quoteRepo) {
    return new JdbcSep38QuoteStore(quoteRepo);
  }

  @Bean
  JdbcStellarPaymentStreamerCursorStore stellarPaymentStreamerCursorStore(
      PaymentStreamerCursorRepo paymentStreamerCursorRepo) {
    return new JdbcStellarPaymentStreamerCursorStore(paymentStreamerCursorRepo);
  }

  @Bean
  public PaymentObservingAccountStore observingAccountStore(PaymentObservingAccountRepo repo) {
    return new PaymentObservingAccountStore(repo);
  }

  @Bean
  JdbcNonceStore nonceStore(JdbcNonceRepo nonceRepo) {
    return new JdbcNonceStore(nonceRepo);
  }
}
