package org.stellar.anchor.platform.component.platform;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.stellar.anchor.api.callback.CustomerIntegration;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.ledger.LedgerClient;
import org.stellar.anchor.metrics.MetricsService;
import org.stellar.anchor.platform.component.sep.ApiClientBeans;
import org.stellar.anchor.platform.config.RpcConfig;
import org.stellar.anchor.platform.data.JdbcTransactionPendingTrustRepo;
import org.stellar.anchor.platform.observer.stellar.PaymentObservingAccountsManager;
import org.stellar.anchor.platform.observer.stellar.SacToAssetMapper;
import org.stellar.anchor.platform.rpc.*;
import org.stellar.anchor.platform.service.RpcService;
import org.stellar.anchor.platform.service.TransactionService;
import org.stellar.anchor.platform.validator.RequestValidator;
import org.stellar.anchor.sep24.Sep24DepositInfoGenerator;
import org.stellar.anchor.sep24.Sep24TransactionStore;
import org.stellar.anchor.sep31.Sep31DepositInfoGenerator;
import org.stellar.anchor.sep31.Sep31TransactionStore;
import org.stellar.anchor.sep38.Sep38QuoteStore;
import org.stellar.anchor.sep6.Sep6DepositInfoGenerator;
import org.stellar.anchor.sep6.Sep6TransactionStore;

@Configuration
@Import(ApiClientBeans.class)
public class RpcActionBeans {

  @Bean
  RpcService rpcService(List<RpcMethodHandler<?>> rpcMethodHandlers, RpcConfig rpcConfig) {
    return new RpcService(rpcMethodHandlers, rpcConfig);
  }

  @Bean
  GetTransactionHandler getTransactionHandler(TransactionService txnService) {
    return new GetTransactionHandler(txnService);
  }

  @Bean
  GetQuoteHandler getQuoteHandler(Sep38QuoteStore sep38QuoteStore) {
    return new GetQuoteHandler(sep38QuoteStore);
  }

  @Bean
  GetTransactionsHandler getTransactionsHandler(TransactionService txnService) {
    return new GetTransactionsHandler(txnService);
  }

  @Bean
  NotifyAmountsUpdatedHandler notifyAmountsUpdatedHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      AssetService assetService,
      EventService eventService,
      MetricsService metricsService) {
    return new NotifyAmountsUpdatedHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService);
  }

  @Bean
  NotifyInteractiveFlowCompletedHandler notifyInteractiveFlowCompletedHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      AssetService assetService,
      EventService eventService,
      MetricsService metricsService) {
    return new NotifyInteractiveFlowCompletedHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService);
  }

  @Bean
  NotifyOffchainFundsAvailableHandler notifyOffchainFundsAvailableHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      AssetService assetService,
      EventService eventService,
      MetricsService metricsService) {
    return new NotifyOffchainFundsAvailableHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService);
  }

  @Bean
  NotifyOffchainFundsPendingHandler notifyOffchainFundsPendingHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      AssetService assetService,
      EventService eventService,
      MetricsService metricsService) {
    return new NotifyOffchainFundsPendingHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService);
  }

  @Bean
  NotifyOffchainFundsReceivedHandler notifyOffchainFundsReceivedHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      AssetService assetService,
      EventService eventService,
      MetricsService metricsService) {
    return new NotifyOffchainFundsReceivedHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService);
  }

  @Bean
  NotifyOffchainFundsSentHandler notifyOffchainFundsSentHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      AssetService assetService,
      EventService eventService,
      MetricsService metricsService) {
    return new NotifyOffchainFundsSentHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService);
  }

  @Bean
  NotifyOnchainFundsReceivedHandler notifyOnchainFundsReceivedHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      LedgerClient ledgerClient,
      AssetService assetService,
      EventService eventService,
      MetricsService metricsService,
      SacToAssetMapper sacToAssetMapper) {
    return new NotifyOnchainFundsReceivedHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        ledgerClient,
        assetService,
        eventService,
        metricsService,
        sacToAssetMapper);
  }

  @Bean
  NotifyOnchainFundsSentHandler notifyOnchainFundsSentHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      LedgerClient ledgerClient,
      AssetService assetService,
      EventService eventService,
      MetricsService metricsService,
      SacToAssetMapper sacToAssetMapper) {
    return new NotifyOnchainFundsSentHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        ledgerClient,
        assetService,
        eventService,
        metricsService,
        sacToAssetMapper);
  }

  @Bean
  NotifyRefundPendingHandler notifyRefundPendingHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      AssetService assetService,
      EventService eventService,
      MetricsService metricsService) {
    return new NotifyRefundPendingHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService);
  }

  @Bean
  NotifyRefundSentHandler notifyRefundSentHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      AssetService assetService,
      EventService eventService,
      MetricsService metricsService) {
    return new NotifyRefundSentHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService);
  }

  @Bean
  NotifyTransactionErrorHandler notifyTransactionErrorHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      AssetService assetService,
      EventService eventService,
      MetricsService metricsService,
      JdbcTransactionPendingTrustRepo transactionPendingTrustRepo) {
    return new NotifyTransactionErrorHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService,
        transactionPendingTrustRepo);
  }

  @Bean
  NotifyTransactionExpiredHandler notifyTransactionExpiredHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      AssetService assetService,
      EventService eventService,
      MetricsService metricsService,
      JdbcTransactionPendingTrustRepo transactionPendingTrustRepo) {
    return new NotifyTransactionExpiredHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService,
        transactionPendingTrustRepo);
  }

  @Bean
  NotifyTransactionOnHoldHandler notifyTransactionOnHoldHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      AssetService assetService,
      EventService eventService,
      MetricsService metricsService) {
    return new NotifyTransactionOnHoldHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService);
  }

  @Bean
  NotifyTransactionRecoveryHandler notifyTransactionRecoveryHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      AssetService assetService,
      EventService eventService,
      MetricsService metricsService) {
    return new NotifyTransactionRecoveryHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService);
  }

  @Bean
  NotifyTrustSetHandler notifyTrustSetHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      AssetService assetService,
      EventService eventService,
      MetricsService metricsService) {
    return new NotifyTrustSetHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService);
  }

  @Bean
  NotifyCustomerInfoUpdatedHandler notifyCustomerInfoUpdatedHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      CustomerIntegration customerIntegration,
      AssetService assetService,
      EventService eventService,
      MetricsService metricsService) {
    return new NotifyCustomerInfoUpdatedHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        customerIntegration,
        assetService,
        eventService,
        metricsService);
  }

  @Bean
  RequestOffchainFundsHandler requestOffchainFundsHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      AssetService assetService,
      EventService eventService,
      MetricsService metricsService) {
    return new RequestOffchainFundsHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService);
  }

  @Bean
  RequestOnchainFundsHandler requestOnchainFundsHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      AssetService assetService,
      Sep6DepositInfoGenerator sep6DepositInfoGenerator,
      Sep24DepositInfoGenerator sep24DepositInfoGenerator,
      Sep31DepositInfoGenerator sep31DepositInfoGenerator,
      PaymentObservingAccountsManager paymentObservingAccountsManager,
      EventService eventService,
      MetricsService metricsService) {
    return new RequestOnchainFundsHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        sep6DepositInfoGenerator,
        sep24DepositInfoGenerator,
        sep31DepositInfoGenerator,
        paymentObservingAccountsManager,
        eventService,
        metricsService);
  }

  @Bean
  RequestTrustlineHandler requestTrustHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      AssetService assetService,
      EventService eventService,
      MetricsService metricsService) {
    return new RequestTrustlineHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService);
  }
}
