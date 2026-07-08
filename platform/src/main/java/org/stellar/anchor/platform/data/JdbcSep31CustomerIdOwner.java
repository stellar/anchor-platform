package org.stellar.anchor.platform.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(name = "sep31_customer_id_owner")
public class JdbcSep31CustomerIdOwner {
  @Id
  @Column(name = "customer_id")
  String customerId;

  @Column(name = "creator_account")
  String creatorAccount;

  @Column(name = "creator_memo")
  String creatorMemo;

  @Column(name = "created_at")
  Instant createdAt;
}
