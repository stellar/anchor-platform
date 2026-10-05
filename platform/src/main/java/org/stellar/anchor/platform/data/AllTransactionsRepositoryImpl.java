package org.stellar.anchor.platform.data;

import static org.stellar.anchor.api.sep.SepTransactionStatus.mergeStatusesList;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Table;
import java.util.List;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.JpaEntityInformationSupport;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.util.TransactionsParams;

public class AllTransactionsRepositoryImpl<T> implements AllTransactionsRepository<T> {
  private final EntityManager em;
  private static final String NL = System.lineSeparator();
  static final int DEFAULT_PAGE_SIZE = 20;
  static final int MAX_PAGE_SIZE = 200;
  static final long MAX_OFFSET = 1_000_000L;

  public AllTransactionsRepositoryImpl(EntityManager em) {
    this.em = em;
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<T> findAllTransactions(TransactionsParams params, Class<T> entityClass) {
    JpaEntityInformation<T, ?> entityInformation =
        JpaEntityInformationSupport.getEntityInformation(entityClass, em);
    Table table = entityInformation.getJavaType().getAnnotation(Table.class);

    if (table == null || table.name().isEmpty()) {
      throw new AssertionError("Class " + entityClass.getName() + " doesn't have table name");
    }

    List<SepTransactionStatus> statuses = params.getStatuses();
    int pageSize = boundedPageSize(params.getPageSize());
    long offset = boundedOffset(params.getPageNumber(), pageSize);

    // Create query
    String nativeQuery =
        String.format(
            "SELECT * FROM %s t %s ORDER BY %s %s NULLS LAST, id ASC LIMIT %d OFFSET %d",
            table.name(),
            statuses == null ? "" : " WHERE t.status in (" + mergeStatusesList(statuses, "'") + ")",
            params.getOrderBy().getTableName(),
            params.getOrder().name(),
            pageSize,
            offset);

    Query query = em.createNativeQuery(nativeQuery, entityClass);

    List<T> results = query.getResultList();

    return results;
  }

  private static int boundedPageSize(Integer requested) {
    if (requested == null || requested <= 0) {
      return DEFAULT_PAGE_SIZE;
    }
    return Math.min(requested, MAX_PAGE_SIZE);
  }

  private static long boundedOffset(Integer pageNumber, int pageSize) {
    if (pageNumber == null || pageNumber <= 0) {
      return 0L;
    }
    long offset = (long) pageNumber * pageSize;
    return Math.min(offset, MAX_OFFSET);
  }
}
