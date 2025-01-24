package org.stellar.anchor.platform.component.custody;

import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.stellar.anchor.api.custody.fireblocks.TransactionDetails;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.config.CustodySecretConfig;
import org.stellar.anchor.ledger.Horizon;
import org.stellar.anchor.platform.config.FireblocksConfig;
import org.stellar.anchor.platform.custody.*;
import org.stellar.anchor.platform.custody.fireblocks.FireblocksApiClient;
import org.stellar.anchor.platform.custody.fireblocks.FireblocksEventService;
import org.stellar.anchor.platform.custody.fireblocks.FireblocksPaymentService;
import org.stellar.anchor.platform.data.JdbcCustodyTransactionRepo;
import org.stellar.anchor.platform.fireblocks.job.FireblocksTransactionsReconciliationJob;

@Configuration
@ConditionalOnProperty(value = "custody.type", havingValue = "fireblocks")
public class FireblocksBeans {

  @Bean
  @ConfigurationProperties(prefix = "custody.fireblocks")
  FireblocksConfig fireblocksConfig(CustodySecretConfig custodySecretConfig) {
    return new FireblocksConfig(custodySecretConfig);
  }

  @Bean
  FireblocksTransactionsReconciliationJob reconciliationJob(
      FireblocksConfig fireblocksConfig,
      CustodyPaymentService<TransactionDetails> custodyPaymentService,
      FireblocksEventService fireblocksEventService,
      CustodyTransactionService custodyTransactionService) {
    return new FireblocksTransactionsReconciliationJob(
        fireblocksConfig, custodyPaymentService, fireblocksEventService, custodyTransactionService);
  }

  @Bean
  FireblocksApiClient fireblocksApiClient(
      @Qualifier("custodyHttpClient") OkHttpClient httpClient, FireblocksConfig fireblocksConfig)
      throws InvalidConfigException {
    return new FireblocksApiClient(httpClient, fireblocksConfig);
  }

  @Bean
  FireblocksEventService fireblocksEventService(
      JdbcCustodyTransactionRepo custodyTransactionRepo,
      Sep6CustodyPaymentHandler sep6CustodyPaymentHandler,
      Sep24CustodyPaymentHandler sep24CustodyPaymentHandler,
      Sep31CustodyPaymentHandler sep31CustodyPaymentHandler,
      Horizon horizon,
      FireblocksConfig fireblocksConfig)
      throws InvalidConfigException {
    return new FireblocksEventService(
        custodyTransactionRepo,
        sep6CustodyPaymentHandler,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        horizon,
        fireblocksConfig);
  }

  @Bean
  CustodyPaymentService<TransactionDetails> custodyPaymentService(
      FireblocksApiClient fireblocksApiClient, FireblocksConfig fireblocksConfig) {
    return new FireblocksPaymentService(fireblocksApiClient, fireblocksConfig);
  }
}
