package org.stellar.anchor.platform.component.platform;

import javax.servlet.Filter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.auth.JwtService;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.filter.ApiKeyFilter;
import org.stellar.anchor.filter.NoneFilter;
import org.stellar.anchor.filter.PlatformAuthJwtFilter;
import org.stellar.anchor.platform.config.PlatformApiConfig;
import org.stellar.anchor.platform.service.TransactionService;
import org.stellar.anchor.sep24.Sep24TransactionStore;
import org.stellar.anchor.sep31.Sep31TransactionStore;
import org.stellar.anchor.sep38.Sep38QuoteStore;

@Configuration
public class PlatformServerBeans {
  /**
   * Register anchor-to-platform token filter.
   *
   * @return Spring Filter Registration Bean
   */
  @Bean
  public FilterRegistrationBean<Filter> anchorToPlatformTokenFilter(PlatformApiConfig config) {
    Filter anchorToPlatformFilter;
    String authSecret = config.getAuth().getSecret();
    switch (config.getAuth().getType()) {
      case JWT:
        JwtService jwtService = new JwtService(null, null, null, null, authSecret);
        anchorToPlatformFilter = new PlatformAuthJwtFilter(jwtService);
        break;

      case API_KEY:
        anchorToPlatformFilter = new ApiKeyFilter(authSecret);
        break;

      default:
        anchorToPlatformFilter = new NoneFilter();
        break;
    }

    FilterRegistrationBean<Filter> registrationBean = new FilterRegistrationBean<>();
    registrationBean.setFilter(anchorToPlatformFilter);
    registrationBean.addUrlPatterns("/transactions/*");
    registrationBean.addUrlPatterns("/transactions");
    registrationBean.addUrlPatterns("/exchange/quotes/*");
    registrationBean.addUrlPatterns("/exchange/quotes");
    return registrationBean;
  }

  @Bean
  TransactionService transactionService(
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      Sep38QuoteStore quoteStore,
      AssetService assetService,
      EventService eventService) {
    return new TransactionService(txn24Store, txn31Store, quoteStore, assetService, eventService);
  }
}
