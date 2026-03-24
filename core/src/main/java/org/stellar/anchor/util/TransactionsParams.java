package org.stellar.anchor.util;

import jakarta.annotation.Nullable;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.data.domain.Sort;
import org.stellar.anchor.api.platform.TransactionsOrderBy;
import org.stellar.anchor.api.sep.SepTransactionStatus;

@Data
@AllArgsConstructor
public class TransactionsParams {
  TransactionsOrderBy orderBy;
  Sort.Direction order;
  @Nullable List<SepTransactionStatus> statuses;
  Integer pageNumber;
  Integer pageSize;
}
