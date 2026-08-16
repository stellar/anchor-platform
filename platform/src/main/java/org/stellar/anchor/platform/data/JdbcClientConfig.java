package org.stellar.anchor.platform.data;

import jakarta.persistence.*;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.stellar.anchor.client.ClientConfig.ClientType;

@Getter
@Setter
@Entity
@Access(AccessType.FIELD)
@Table(name = "client_config")
@NoArgsConstructor
public class JdbcClientConfig {
  @Id private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "type")
  private ClientType type;

  @Column(name = "allow_any_destination")
  private boolean allowAnyDestination;

  @Column(name = "callback_url_sep6")
  private String callbackUrlSep6;

  @Column(name = "callback_url_sep24")
  private String callbackUrlSep24;

  @Column(name = "callback_url_sep31")
  private String callbackUrlSep31;

  @Column(name = "callback_url_sep12")
  private String callbackUrlSep12;

  @ElementCollection
  @CollectionTable(name = "client_domain", joinColumns = @JoinColumn(name = "client_name"))
  @Column(name = "domain")
  private Set<String> domains = new HashSet<>();

  @ElementCollection
  @CollectionTable(name = "client_signing_key", joinColumns = @JoinColumn(name = "client_name"))
  @Column(name = "signing_key")
  private Set<String> signingKeys = new HashSet<>();

  @ElementCollection
  @CollectionTable(
      name = "client_destination_account",
      joinColumns = @JoinColumn(name = "client_name"))
  @Column(name = "destination_account")
  private Set<String> destinationAccounts = new HashSet<>();
}
