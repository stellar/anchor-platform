package org.stellar.reference.dao

import java.time.Instant
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.stellar.anchor.util.GsonUtils
import org.stellar.reference.model.FeeDetails
import org.stellar.reference.model.Quote

interface QuoteRepository {
  fun get(id: String): Quote?
  fun create(quote: Quote): String?
}

class JdbcQuoteRepository(private val db: Database) : QuoteRepository {
  init {
    transaction(db) { SchemaUtils.create(Quotes) }
  }

  override fun get(id: String): Quote? =
    transaction(db) {
      Quotes.select { Quotes.id.eq(id) }
        .mapNotNull { it ->
          Quote(
            id = id,
            price = it[Quotes.price],
            totalPrice = it[Quotes.totalPrice],
            expiresAt = it[Quotes.expiresAt]?.let { ts -> Instant.ofEpochMilli(ts) },
            createdAt = it[Quotes.createdAt]?.let { ts -> Instant.ofEpochMilli(ts) },
            sellAsset = it[Quotes.sellAsset],
            sellAmount = it[Quotes.sellAmount],
            sellDeliveryMethod = it[Quotes.sellDeliveryMethod],
            buyAsset = it[Quotes.buyAsset],
            buyAmount = it[Quotes.buyAmount],
            buyDeliveryMethod = it[Quotes.buyDeliveryMethod],
            countryCode = it[Quotes.countryCode],
            clientId = it[Quotes.clientId],
            fee =
              it[Quotes.fee]?.let { fee ->
                GsonUtils.getInstance().fromJson(fee, FeeDetails::class.java)
              }
          )
        }
        .singleOrNull()
    }

  override fun create(quote: Quote): String? =
    transaction(db) {
        Quotes.insert {
          it[id] = quote.id
          it[price] = quote.price
          it[totalPrice] = quote.totalPrice
          it[expiresAt] = quote.expiresAt?.toEpochMilli()
          it[createdAt] = quote.createdAt?.toEpochMilli()
          it[sellAsset] = quote.sellAsset
          it[sellAmount] = quote.sellAmount
          it[sellDeliveryMethod] = quote.sellDeliveryMethod
          it[buyAsset] = quote.buyAsset
          it[buyAmount] = quote.buyAmount
          it[buyDeliveryMethod] = quote.buyDeliveryMethod
          it[countryCode] = quote.countryCode
          it[clientId] = quote.clientId
          it[fee] = GsonUtils.getInstance().toJson(quote.fee)
        }
      }
      .resultedValues
      ?.firstOrNull()
      ?.get(Quotes.id)
}
