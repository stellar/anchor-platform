package org.stellar.anchor.api.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FeeDetails {
  String total;
  String asset;
  List<FeeDescription> details;

  public FeeDetails(String total, String asset) {
    this.total = total;
    this.asset = asset;
  }

  public void addFeeDetail(FeeDescription feeDetail) {
    if (feeDetail == null || feeDetail.amount == null) {
      return;
    }
    BigDecimal detailAmount = new BigDecimal(feeDetail.getAmount());
    if (detailAmount.compareTo(BigDecimal.ZERO) == 0) {
      return;
    }

    BigDecimal total = new BigDecimal(this.total);
    total = total.add(detailAmount);
    this.total = formatAmount(total);

    if (this.details == null) {
      this.details = new ArrayList<>();
    }
    this.details.add(feeDetail);
  }

  private String formatAmount(BigDecimal amount) {
    int decimals = 4;
    BigDecimal newAmount = amount.setScale(decimals, RoundingMode.HALF_DOWN);

    DecimalFormat df = new DecimalFormat();
    df.setMaximumFractionDigits(decimals);
    df.setMinimumFractionDigits(2);
    df.setGroupingUsed(false);

    return df.format(newAmount);
  }
}
