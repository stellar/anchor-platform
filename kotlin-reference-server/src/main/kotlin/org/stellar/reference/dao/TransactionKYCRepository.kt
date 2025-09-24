package org.stellar.reference.dao

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.stellar.anchor.util.GsonUtils
import org.stellar.reference.model.TransactionKYC

interface TransactionKYCRepository {
  fun get(transactionId: String): TransactionKYC?

  fun create(transactionKYC: TransactionKYC)

  fun update(transactionId: String, requiredFields: String, customerId: String?)

  fun delete(transactionId: String)
}

class JdbcTransactionKYCRepository(private val db: Database) : TransactionKYCRepository {
  init {
    transaction(db) { SchemaUtils.create(TransactionKYCs) }
  }

  override fun get(transactionId: String): TransactionKYC? =
    transaction(db) {
        TransactionKYCs.select { TransactionKYCs.transactionId.eq(transactionId) }
          .mapNotNull {
            TransactionKYC(
              transactionId = it[TransactionKYCs.transactionId],
              requiredFields =
                GsonUtils.getInstance()
                  .fromJson<List<String>>(it[TransactionKYCs.requiredFields], List::class.java),
            )
          }
      }
      .singleOrNull()

  override fun create(transactionKYC: TransactionKYC) {
    transaction(db) {
      TransactionKYCs.insert {
        it[transactionId] = transactionKYC.transactionId
        it[requiredFields] = GsonUtils.getInstance().toJson(transactionKYC.requiredFields)
      }
    }
  }

  override fun update(transactionId: String, requiredFields: String, customerId: String?) {
    transaction(db) {
      TransactionKYCs.update({ TransactionKYCs.transactionId.eq(transactionId) }) {
        it[TransactionKYCs.requiredFields] = requiredFields
      }
    }
  }

  override fun delete(transactionId: String) {
    transaction(db) {
      TransactionKYCs.deleteWhere { TransactionKYCs.transactionId.eq(transactionId) }
    }
  }
}
