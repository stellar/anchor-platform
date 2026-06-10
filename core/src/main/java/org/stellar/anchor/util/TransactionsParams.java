package org.stellar.anchor.util;

import jakarta.annotation.Nullable;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.data.domain.Sort;
import org.stellar.anchor.api.platform.TransactionsOrderBy;
import org.stellar.anchor.api.sep.SepTransactionStatus;

/**
 * Offset-based pagination parameters for the internal Platform API (GET /transactions).
 *
 * <p>Uses {@code pageNumber} and {@code pageSize} for traditional page-based pagination.
 *
 * <p>Not to be confused with {@link TransactionQueryLimits}, which provides cursor-based query
 * limits for the client-facing SEP-6/SEP-24 API endpoints.
 */
@Data
@AllArgsConstructor
public class TransactionsParams {
  TransactionsOrderBy orderBy;
  Sort.Direction order;
  @Nullable List<SepTransactionStatus> statuses;
  Integer pageNumber;
  Integer pageSize;
}
