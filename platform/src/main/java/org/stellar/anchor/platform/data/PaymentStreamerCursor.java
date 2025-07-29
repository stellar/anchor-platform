package org.stellar.anchor.platform.data;

import com.google.gson.annotations.SerializedName;
import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Access(AccessType.FIELD)
@Table(name = "stellar_payment_observer_page_token")
public class PaymentStreamerCursor {
  public static final String SINGLETON_ID = "SINGLETON_ID";

  @Id
  @SerializedName("id")
  String id = SINGLETON_ID;

  String horizonCursor;
  String stellarRpcCursor;
}
