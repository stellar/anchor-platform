package org.stellar.anchor.platform.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.stellar.anchor.sep24.Sep24RefundPayment;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class JdbcSep24RefundPayment implements Sep24RefundPayment {
  String id;
  String idType;
  String amount;
  String fee;
}
