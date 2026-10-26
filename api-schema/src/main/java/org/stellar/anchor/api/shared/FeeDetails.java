package org.stellar.anchor.api.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

  // Stellar amounts carry at most 7 decimals (stroops); a lower scale would drop part of a fee.
  private static final int MAX_DECIMALS = 7;

  private String formatAmount(BigDecimal amount) {
    BigDecimal newAmount = amount.setScale(MAX_DECIMALS, RoundingMode.HALF_DOWN);

    // the symbols are fixed so that the result does not depend on the JVM default locale
    DecimalFormat df = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
    df.setMaximumFractionDigits(MAX_DECIMALS);
    df.setMinimumFractionDigits(2);
    df.setGroupingUsed(false);

    return df.format(newAmount);
  }
}
