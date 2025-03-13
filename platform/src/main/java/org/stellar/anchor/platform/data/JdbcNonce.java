package org.stellar.anchor.platform.data;

import com.google.gson.annotations.SerializedName;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.stellar.anchor.auth.Nonce;

@Getter
@Setter
@Entity
@Access(AccessType.FIELD)
@Table(name = "nonce")
@NoArgsConstructor
public class JdbcNonce implements Nonce {
  @Id String id;

  @SerializedName("used")
  @Column(name = "used")
  Boolean used;

  @SerializedName("expires_at")
  @Column(name = "expires_at")
  Instant expiresAt;
}
