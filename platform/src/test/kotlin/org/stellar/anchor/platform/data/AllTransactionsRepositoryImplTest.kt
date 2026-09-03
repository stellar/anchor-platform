package org.stellar.anchor.platform.data

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.support.JpaEntityInformation
import org.springframework.data.jpa.repository.support.JpaEntityInformationSupport
import org.stellar.anchor.api.platform.TransactionsOrderBy
import org.stellar.anchor.util.TransactionsParams

class AllTransactionsRepositoryImplTest {

  private lateinit var em: EntityManager
  private lateinit var repo: AllTransactionsRepositoryImpl<JdbcSep24Transaction>
  private val querySlot = slot<String>()

  @BeforeEach
  fun setUp() {
    em = mockk()
    repo = AllTransactionsRepositoryImpl(em)

    val entityInformation = mockk<JpaEntityInformation<JdbcSep24Transaction, *>>()
    every { entityInformation.javaType } returns JdbcSep24Transaction::class.java
    mockkStatic(JpaEntityInformationSupport::class)
    every {
      JpaEntityInformationSupport.getEntityInformation(JdbcSep24Transaction::class.java, em)
    } returns entityInformation

    val query = mockk<Query>()
    every { query.resultList } returns emptyList<JdbcSep24Transaction>()
    every { em.createNativeQuery(capture(querySlot), JdbcSep24Transaction::class.java) } returns
      query
  }

  @AfterEach
  fun tearDown() {
    unmockkStatic(JpaEntityInformationSupport::class)
  }

  private fun params(pageNumber: Int?, pageSize: Int?) =
    TransactionsParams(
      TransactionsOrderBy.CREATED_AT,
      Sort.Direction.ASC,
      null,
      pageNumber,
      pageSize
    )

  @Test
  fun `page_size is capped at the maximum even when a huge value is requested`() {
    repo.findAllTransactions(params(0, 999999999), JdbcSep24Transaction::class.java)
    assertTrue(querySlot.captured.contains("LIMIT ${AllTransactionsRepositoryImpl.MAX_PAGE_SIZE} "))
  }

  @Test
  fun `page_size falls back to the default when null`() {
    repo.findAllTransactions(params(0, null), JdbcSep24Transaction::class.java)
    assertTrue(
      querySlot.captured.contains("LIMIT ${AllTransactionsRepositoryImpl.DEFAULT_PAGE_SIZE} ")
    )
  }

  @Test
  fun `page_size falls back to the default when zero or negative`() {
    repo.findAllTransactions(params(0, 0), JdbcSep24Transaction::class.java)
    assertTrue(
      querySlot.captured.contains("LIMIT ${AllTransactionsRepositoryImpl.DEFAULT_PAGE_SIZE} ")
    )

    repo.findAllTransactions(params(0, -5), JdbcSep24Transaction::class.java)
    assertTrue(
      querySlot.captured.contains("LIMIT ${AllTransactionsRepositoryImpl.DEFAULT_PAGE_SIZE} ")
    )
  }

  @Test
  fun `a normal page_size within bounds is left untouched`() {
    repo.findAllTransactions(params(2, 50), JdbcSep24Transaction::class.java)
    assertTrue(querySlot.captured.contains("LIMIT 50 OFFSET 100"))
  }

  @Test
  fun `negative page_number does not produce a negative offset`() {
    repo.findAllTransactions(params(-1, 50), JdbcSep24Transaction::class.java)
    assertTrue(querySlot.captured.contains("LIMIT 50 OFFSET 0"))
  }

  @Test
  fun `null page_number defaults to the first page`() {
    repo.findAllTransactions(params(null, 50), JdbcSep24Transaction::class.java)
    assertTrue(querySlot.captured.contains("LIMIT 50 OFFSET 0"))
  }
}
