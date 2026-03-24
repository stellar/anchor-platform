package org.stellar.anchor.platform.component.share;

import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.stellar.anchor.custody.CustodyService;
import org.stellar.anchor.platform.apiclient.CustodyApiClient;
import org.stellar.anchor.platform.service.CustodyServiceImpl;

@Configuration
public class SharedCustodyBeans {

  @Bean
  CustodyService custodyService(Optional<CustodyApiClient> custodyApiClient) {
    return new CustodyServiceImpl(custodyApiClient);
  }
}
