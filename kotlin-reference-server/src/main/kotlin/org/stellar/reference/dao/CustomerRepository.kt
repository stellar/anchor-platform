package org.stellar.reference.dao

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.transactions.transaction
import org.stellar.reference.model.Customer

interface CustomerRepository {
  fun get(id: String): Customer?

  fun get(stellarAccount: String, memo: String?, memoType: String?): Customer?

  fun create(customer: Customer): String?

  fun update(customer: Customer)

  fun delete(id: String)
}

class JdbcCustomerRepository(private val db: Database) : CustomerRepository {
  init {
    transaction(db) { SchemaUtils.create(Customers) }
  }

  override fun get(id: String): Customer? =
    transaction(db) {
        Customers.select { Customers.id.eq(id) }
          .mapNotNull {
            Customer(
              id = id,
              stellarAccount = it[Customers.stellarAccount],
              memo = it[Customers.memo],
              memoType = it[Customers.memoType],
              firstName = it[Customers.firstName],
              lastName = it[Customers.lastName],
              additionalName = it[Customers.additionalName],
              addressCountryCode = it[Customers.addressCountryCode],
              stateOrProvince = it[Customers.stateOrProvince],
              city = it[Customers.city],
              postalCode = it[Customers.postalCode],
              address = it[Customers.address],
              mobileNumber = it[Customers.mobileNumber],
              emailAddress = it[Customers.emailAddress],
              birthDate = it[Customers.birthDate],
              birthPlace = it[Customers.birthPlace],
              birthCountryCode = it[Customers.birthCountryCode],
              bankName = it[Customers.bankName],
              bankAccountNumber = it[Customers.bankAccountNumber],
              bankAccountType = it[Customers.bankAccountType],
              bankNumber = it[Customers.bankNumber],
              bankPhoneNumber = it[Customers.bankPhoneNumber],
              bankBranchNumber = it[Customers.bankBranchNumber],
              externalTransferMemo = it[Customers.externalTransferMemo],
              clabeNumber = it[Customers.clabeNumber],
              cbuNumber = it[Customers.cbuNumber],
              cbuAlias = it[Customers.cbuAlias],
              mobileMoneyNumber = it[Customers.mobileMoneyNumber],
              mobileMoneyProvider = it[Customers.mobileMoneyProvider],
              cryptoAddress = it[Customers.cryptoAddress],
              cryptoMemo = it[Customers.cryptoMemo],
              taxId = it[Customers.taxId],
              taxIdName = it[Customers.taxIdName],
              occupation = it[Customers.occupation],
              employerName = it[Customers.employerName],
              employerAddress = it[Customers.employerAddress],
              languageCode = it[Customers.languageCode],
              idType = it[Customers.idType],
              idCountryCode = it[Customers.idCountryCode],
              idIssueDate = it[Customers.idIssueDate],
              idExpirationDate = it[Customers.idExpirationDate],
              idNumber = it[Customers.idNumber],
              photoIdFront = it[Customers.photoIdFront],
              photoIdBack = it[Customers.photoIdBack],
              notaryApprovalOfPhotoId = it[Customers.notaryApprovalOfPhotoId],
              ipAddress = it[Customers.ipAddress],
              photoProofResidence = it[Customers.photoProofResidence],
              sex = it[Customers.sex],
              photoProofOfIncome = it[Customers.photoProofOfIncome],
              proofOfLiveness = it[Customers.proofOfLiveness],
              referralId = it[Customers.referralId],
            )
          }
      }
      .singleOrNull()

  override fun get(stellarAccount: String, memo: String?, memoType: String?): Customer? {
    val query =
      when {
        memo == null && memoType == null -> {
          Customers.stellarAccount.eq(stellarAccount) and
            Customers.memo.isNull() and
            Customers.memoType.isNull()
        }
        else -> {
          Customers.stellarAccount.eq(stellarAccount) and
            Customers.memo.eq(memo!!) and
            Customers.memoType.eq(memoType!!)
        }
      }
    return transaction(db) {
        Customers.select { query }
          .mapNotNull {
            Customer(
              id = it[Customers.id],
              stellarAccount = it[Customers.stellarAccount],
              memo = it[Customers.memo],
              memoType = it[Customers.memoType],
              firstName = it[Customers.firstName],
              lastName = it[Customers.lastName],
              additionalName = it[Customers.additionalName],
              addressCountryCode = it[Customers.addressCountryCode],
              stateOrProvince = it[Customers.stateOrProvince],
              city = it[Customers.city],
              postalCode = it[Customers.postalCode],
              address = it[Customers.address],
              mobileNumber = it[Customers.mobileNumber],
              emailAddress = it[Customers.emailAddress],
              birthDate = it[Customers.birthDate],
              birthPlace = it[Customers.birthPlace],
              birthCountryCode = it[Customers.birthCountryCode],
              bankName = it[Customers.bankName],
              bankAccountNumber = it[Customers.bankAccountNumber],
              bankAccountType = it[Customers.bankAccountType],
              bankNumber = it[Customers.bankNumber],
              bankPhoneNumber = it[Customers.bankPhoneNumber],
              bankBranchNumber = it[Customers.bankBranchNumber],
              externalTransferMemo = it[Customers.externalTransferMemo],
              clabeNumber = it[Customers.clabeNumber],
              cbuNumber = it[Customers.cbuNumber],
              cbuAlias = it[Customers.cbuAlias],
              mobileMoneyNumber = it[Customers.mobileMoneyNumber],
              mobileMoneyProvider = it[Customers.mobileMoneyProvider],
              cryptoAddress = it[Customers.cryptoAddress],
              cryptoMemo = it[Customers.cryptoMemo],
              taxId = it[Customers.taxId],
              taxIdName = it[Customers.taxIdName],
              occupation = it[Customers.occupation],
              employerName = it[Customers.employerName],
              employerAddress = it[Customers.employerAddress],
              languageCode = it[Customers.languageCode],
              idType = it[Customers.idType],
              idCountryCode = it[Customers.idCountryCode],
              idIssueDate = it[Customers.idIssueDate],
              idExpirationDate = it[Customers.idExpirationDate],
              idNumber = it[Customers.idNumber],
              photoIdFront = it[Customers.photoIdFront],
              photoIdBack = it[Customers.photoIdBack],
              notaryApprovalOfPhotoId = it[Customers.notaryApprovalOfPhotoId],
              ipAddress = it[Customers.ipAddress],
              photoProofResidence = it[Customers.photoProofResidence],
              sex = it[Customers.sex],
              photoProofOfIncome = it[Customers.photoProofOfIncome],
              proofOfLiveness = it[Customers.proofOfLiveness],
              referralId = it[Customers.referralId],
            )
          }
      }
      .singleOrNull()
  }

  override fun create(customer: Customer): String? =
    transaction(db) {
        Customers.insert {
          it[id] = customer.id!!
          it[stellarAccount] = customer.stellarAccount
          it[memo] = customer.memo
          it[memoType] = customer.memoType
          it[firstName] = customer.firstName
          it[lastName] = customer.lastName
          it[additionalName] = customer.additionalName
          it[addressCountryCode] = customer.addressCountryCode
          it[stateOrProvince] = customer.stateOrProvince
          it[city] = customer.city
          it[postalCode] = customer.postalCode
          it[address] = customer.address
          it[mobileNumber] = customer.mobileNumber
          it[emailAddress] = customer.emailAddress
          it[birthDate] = customer.birthDate
          it[birthPlace] = customer.birthPlace
          it[birthCountryCode] = customer.birthCountryCode
          it[bankName] = customer.bankName
          it[bankAccountNumber] = customer.bankAccountNumber
          it[bankAccountType] = customer.bankAccountType
          it[bankNumber] = customer.bankNumber
          it[bankPhoneNumber] = customer.bankPhoneNumber
          it[bankBranchNumber] = customer.bankBranchNumber
          it[externalTransferMemo] = customer.externalTransferMemo
          it[clabeNumber] = customer.clabeNumber
          it[cbuNumber] = customer.cbuNumber
          it[cbuAlias] = customer.cbuAlias
          it[mobileMoneyNumber] = customer.mobileMoneyNumber
          it[mobileMoneyProvider] = customer.mobileMoneyProvider
          it[cryptoAddress] = customer.cryptoAddress
          it[cryptoMemo] = customer.cryptoMemo
          it[taxId] = customer.taxId
          it[taxIdName] = customer.taxIdName
          it[occupation] = customer.occupation
          it[employerName] = customer.employerName
          it[employerAddress] = customer.employerAddress
          it[languageCode] = customer.languageCode
          it[idType] = customer.idType
          it[idCountryCode] = customer.idCountryCode
          it[idIssueDate] = customer.idIssueDate
          it[idExpirationDate] = customer.idExpirationDate
          it[idNumber] = customer.idNumber
          it[photoIdFront] = customer.photoIdFront
          it[photoIdBack] = customer.photoIdBack
          it[notaryApprovalOfPhotoId] = customer.notaryApprovalOfPhotoId
          it[ipAddress] = customer.ipAddress
          it[photoProofResidence] = customer.photoProofResidence
          it[sex] = customer.sex
          it[photoProofOfIncome] = customer.photoProofOfIncome
          it[proofOfLiveness] = customer.proofOfLiveness
          it[referralId] = customer.referralId
        }
      }
      .resultedValues
      ?.firstOrNull()
      ?.get(Customers.id)

  override fun update(customer: Customer): Unit =
    transaction(db) {
      Customers.update({ Customers.id.eq(customer.id!!) }) {
        it[stellarAccount] = customer.stellarAccount
        it[memo] = customer.memo
        it[memoType] = customer.memoType
        it[firstName] = customer.firstName
        it[lastName] = customer.lastName
        it[additionalName] = customer.additionalName
        it[addressCountryCode] = customer.addressCountryCode
        it[stateOrProvince] = customer.stateOrProvince
        it[city] = customer.city
        it[address] = customer.address
        it[mobileNumber] = customer.mobileNumber
        it[emailAddress] = customer.emailAddress
        it[birthDate] = customer.birthDate
        it[birthPlace] = customer.birthPlace
        it[birthCountryCode] = customer.birthCountryCode
        it[bankName] = customer.bankName
        it[bankAccountNumber] = customer.bankAccountNumber
        it[bankAccountType] = customer.bankAccountType
        it[bankNumber] = customer.bankNumber
        it[bankPhoneNumber] = customer.bankPhoneNumber
        it[bankBranchNumber] = customer.bankBranchNumber
        it[externalTransferMemo] = customer.externalTransferMemo
        it[clabeNumber] = customer.clabeNumber
        it[cbuNumber] = customer.cbuNumber
        it[cbuAlias] = customer.cbuAlias
        it[mobileMoneyNumber] = customer.mobileMoneyNumber
        it[mobileMoneyProvider] = customer.mobileMoneyProvider
        it[cryptoAddress] = customer.cryptoAddress
        it[cryptoMemo] = customer.cryptoMemo
        it[taxId] = customer.taxId
        it[taxIdName] = customer.taxIdName
        it[occupation] = customer.occupation
        it[employerName] = customer.employerName
        it[employerAddress] = customer.employerAddress
        it[languageCode] = customer.languageCode
        it[idType] = customer.idType
        it[idCountryCode] = customer.idCountryCode
        it[idIssueDate] = customer.idIssueDate
        it[idExpirationDate] = customer.idExpirationDate
        it[idNumber] = customer.idNumber
        it[photoIdFront] = customer.photoIdFront
        it[photoIdBack] = customer.photoIdBack
        it[notaryApprovalOfPhotoId] = customer.notaryApprovalOfPhotoId
        it[ipAddress] = customer.ipAddress
        it[photoProofResidence] = customer.photoProofResidence
        it[sex] = customer.sex
        it[photoProofOfIncome] = customer.photoProofOfIncome
        it[proofOfLiveness] = customer.proofOfLiveness
        it[referralId] = customer.referralId
      }
    }

  override fun delete(id: String): Unit =
    transaction(db) { Customers.deleteWhere { Customers.id.eq(id) } }
}
