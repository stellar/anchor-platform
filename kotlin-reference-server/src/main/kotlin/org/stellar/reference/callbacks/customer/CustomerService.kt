package org.stellar.reference.callbacks.customer

import java.util.*
import org.stellar.anchor.api.callback.GetCustomerRequest
import org.stellar.anchor.api.callback.GetCustomerResponse
import org.stellar.anchor.api.callback.PutCustomerRequest
import org.stellar.anchor.api.callback.PutCustomerResponse
import org.stellar.anchor.api.sep.sep12.ProvidedFieldStatus
import org.stellar.anchor.api.sep.sep12.Sep12Status
import org.stellar.anchor.api.shared.CustomerField
import org.stellar.anchor.api.shared.ProvidedCustomerField
import org.stellar.reference.callbacks.BadRequestException
import org.stellar.reference.callbacks.NotFoundException
import org.stellar.reference.dao.CustomerRepository
import org.stellar.reference.dao.TransactionKYCRepository
import org.stellar.reference.log
import org.stellar.reference.model.Customer
import org.stellar.reference.model.TransactionKYC
import org.stellar.reference.service.SepHelper

class CustomerService(
  private val customerRepository: CustomerRepository,
  private val transactionKYCRepository: TransactionKYCRepository,
  private val sepHelper: SepHelper,
) {
  suspend fun getCustomer(request: GetCustomerRequest): GetCustomerResponse {
    val customer =
      when {
        // FIXME: This implementation relies on transaction.customer.sender account and memo to be
        // set which means it only works for SEP-6 transactions.
        request.transactionId != null -> {
          val transaction = sepHelper.getTransaction(request.transactionId)
          val txnCustomer =
            if ("sep31-receiver" == request.type) {
              transaction.customers!!.receiver
            } else {
              transaction.customers!!.sender
            }
          val memoType = if (txnCustomer!!.memo != null) "id" else null

          val customer =
            getCustomerFromTransaction(request.transactionId, request.type)
              ?: Customer(
                stellarAccount = txnCustomer.account,
                memo = txnCustomer.memo,
                memoType = memoType
              )

          // Determine if this transaction requires additional fields
          val transactionKYC = transactionKYCRepository.get(request.transactionId)
          if (transactionKYC != null) {
            return convertCustomerToResponse(customer, request.type, transactionKYC.requiredFields)
          }

          customer
        }
        request.id != null -> {
          val customer =
            customerRepository.get(request.id)
              ?: throw NotFoundException("customer for 'id' '${request.id}' not found", request.id)
          assertCustomerMatchesRequest(
            customer,
            request.id,
            request.account,
            request.memo,
            request.memoType
          )
          customer
        }
        request.account != null -> {
          customerRepository.get(request.account, request.memo, request.memoType)
            ?: Customer(
              stellarAccount = request.account,
              memo = request.memo,
              memoType = request.memoType,
            )
        }
        else -> {
          throw BadRequestException("Either id or account must be provided")
        }
      }
    return convertCustomerToResponse(customer, request.type, emptyList())
  }

  suspend fun upsertCustomer(request: PutCustomerRequest): PutCustomerResponse {
    log.info { "Upserting customer: $request" }
    val customer =
      when {
        request.transactionId != null -> {
          getCustomerFromTransaction(request.transactionId, request.type)
        }
        request.id != null -> {
          val customer = customerRepository.get(request.id)
          if (customer != null) {
            assertCustomerMatchesRequest(
              customer,
              request.id,
              request.account,
              request.memo,
              request.memoType
            )
          }
          customer
        }
        request.account != null ->
          customerRepository.get(request.account, request.memo, request.memoType)
        else -> {
          throw BadRequestException("Either id or account must be provided")
        }
      }

    // Update the customer if it exists, otherwise create a new one.
    val updatedCustomer =
      if (customer != null) {
        val updatedCustomer =
          customer.copy(
            firstName = request.firstName ?: customer.firstName,
            lastName = request.lastName ?: customer.lastName,
            additionalName = request.additionalName ?: customer.additionalName,
            addressCountryCode = request.addressCountryCode ?: customer.addressCountryCode,
            stateOrProvince = request.stateOrProvince ?: customer.stateOrProvince,
            city = request.city ?: customer.city,
            postalCode = request.postalCode ?: customer.postalCode,
            address = request.address ?: customer.address,
            mobileNumber = request.mobileNumber ?: customer.mobileNumber,
            emailAddress = request.emailAddress ?: customer.emailAddress,
            birthDate = request.birthDate ?: customer.birthDate,
            birthPlace = request.birthPlace ?: customer.birthPlace,
            birthCountryCode = request.birthCountryCode ?: customer.birthCountryCode,
            bankName = request.bankName ?: customer.bankName,
            bankAccountNumber = request.bankAccountNumber ?: customer.bankAccountNumber,
            bankAccountType = request.bankAccountType ?: customer.bankAccountType,
            bankNumber = request.bankNumber ?: customer.bankNumber,
            bankPhoneNumber = request.bankPhoneNumber ?: customer.bankPhoneNumber,
            bankBranchNumber = request.bankBranchNumber ?: customer.bankBranchNumber,
            externalTransferMemo = request.externalTransferMemo ?: customer.externalTransferMemo,
            clabeNumber = request.clabeNumber ?: customer.clabeNumber,
            cbuNumber = request.cbuNumber ?: customer.cbuNumber,
            cbuAlias = request.cbuAlias ?: customer.cbuAlias,
            mobileMoneyNumber = request.mobileMoneyNumber ?: customer.mobileMoneyNumber,
            mobileMoneyProvider = request.mobileMoneyProvider ?: customer.mobileMoneyProvider,
            cryptoAddress = request.cryptoAddress ?: customer.cryptoAddress,
            cryptoMemo = request.cryptoMemo ?: customer.cryptoMemo,
            taxId = request.taxId ?: customer.taxId,
            taxIdName = request.taxIdName ?: customer.taxIdName,
            occupation = request.occupation ?: customer.occupation,
            employerName = request.employerName ?: customer.employerName,
            employerAddress = request.employerAddress ?: customer.employerAddress,
            languageCode = request.languageCode ?: customer.languageCode,
            idType = request.idType ?: customer.idType,
            idCountryCode = request.idCountryCode ?: customer.idCountryCode,
            idIssueDate = request.idIssueDate ?: customer.idIssueDate,
            idExpirationDate = request.idExpirationDate ?: customer.idExpirationDate,
            idNumber = request.idNumber ?: customer.idNumber,
            photoIdFront = request.photoIdFront ?: customer.photoIdFront,
            photoIdBack = request.photoIdBack ?: customer.photoIdBack,
            notaryApprovalOfPhotoId = request.notaryApprovalOfPhotoId
                ?: customer.notaryApprovalOfPhotoId,
            ipAddress = request.ipAddress ?: customer.ipAddress,
            photoProofResidence = request.photoProofResidence ?: customer.photoProofResidence,
            sex = request.sex ?: customer.sex,
            photoProofOfIncome = request.photoProofOfIncome ?: customer.photoProofOfIncome,
            proofOfLiveness = request.proofOfLiveness ?: customer.proofOfLiveness,
            referralId = request.referralId ?: customer.referralId,
          )
        customerRepository.update(updatedCustomer)
        getCustomer(GetCustomerRequest.builder().id(customer.id!!).build())
      } else {
        val id = UUID.randomUUID().toString()
        customerRepository.create(
          Customer(
            id = id,
            stellarAccount = request.account,
            memo = request.memo,
            memoType = request.memoType,
            firstName = request.firstName,
            lastName = request.lastName,
            additionalName = request.additionalName,
            addressCountryCode = request.addressCountryCode,
            stateOrProvince = request.stateOrProvince,
            city = request.city,
            postalCode = request.postalCode,
            address = request.address,
            mobileNumber = request.mobileNumber,
            emailAddress = request.emailAddress,
            birthDate = request.birthDate,
            birthPlace = request.birthPlace,
            birthCountryCode = request.birthCountryCode,
            bankName = request.bankName,
            bankAccountNumber = request.bankAccountNumber,
            bankAccountType = request.bankAccountType,
            bankNumber = request.bankNumber,
            bankPhoneNumber = request.bankPhoneNumber,
            bankBranchNumber = request.bankBranchNumber,
            externalTransferMemo = request.externalTransferMemo,
            clabeNumber = request.clabeNumber,
            cbuNumber = request.cbuNumber,
            cbuAlias = request.cbuAlias,
            mobileMoneyNumber = request.mobileMoneyNumber,
            mobileMoneyProvider = request.mobileMoneyProvider,
            cryptoAddress = request.cryptoAddress,
            cryptoMemo = request.cryptoMemo,
            taxId = request.taxId,
            taxIdName = request.taxIdName,
            occupation = request.occupation,
            employerName = request.employerName,
            employerAddress = request.employerAddress,
            languageCode = request.languageCode,
            idType = request.idType,
            idCountryCode = request.idCountryCode,
            idIssueDate = request.idIssueDate,
            idExpirationDate = request.idExpirationDate,
            idNumber = request.idNumber,
            photoIdFront = request.photoIdFront,
            photoIdBack = request.photoIdBack,
            notaryApprovalOfPhotoId = request.notaryApprovalOfPhotoId,
            ipAddress = request.ipAddress,
            photoProofResidence = request.photoProofResidence,
            sex = request.sex,
            photoProofOfIncome = request.photoProofOfIncome,
            proofOfLiveness = request.proofOfLiveness,
            referralId = request.referralId,
          )
        )
        getCustomer(GetCustomerRequest.builder().id(id).build())
      }

    return PutCustomerResponse.builder()
      .id(updatedCustomer.id)
      .status(updatedCustomer.status)
      .fields(updatedCustomer.fields)
      .providedFields(updatedCustomer.providedFields)
      .message(updatedCustomer.message)
      .build()
  }

  fun requestAdditionalFieldsForTransaction(id: String, requiredFields: List<String>) {
    val kyc = TransactionKYC(id, requiredFields)
    if (transactionKYCRepository.get(id) != null) {
      transactionKYCRepository.delete(id)
    }
    transactionKYCRepository.create(kyc)
  }

  fun deleteCustomer(id: String) {
    customerRepository.delete(id)
  }

  fun invalidateClabe(id: String) {
    try {
      customerRepository.update(customerRepository.get(id)!!.copy(clabeNumber = null))
    } catch (e: Exception) {
      throw NotFoundException("customer for 'id' '$id' not found", id)
    }
  }

  private suspend fun getCustomerFromTransaction(transactionId: String, type: String?): Customer? {
    val transaction = sepHelper.getTransaction(transactionId)
    val txnCustomer =
      if (type == "sep31-receiver") {
        transaction.customers!!.receiver
      } else {
        transaction.customers!!.sender
      }
    val memoType = if (txnCustomer!!.memo != null) "id" else null

    return when {
      txnCustomer.id != null -> customerRepository.get(txnCustomer.id)
      txnCustomer.account != null ->
        customerRepository.get(txnCustomer.account, txnCustomer.memo, memoType)
      else -> throw BadRequestException("Either id or account must be provided")
    }
  }

  private fun assertCustomerMatchesRequest(
    customer: Customer,
    requestId: String,
    requestAccount: String?,
    requestMemo: String?,
    requestMemoType: String?,
  ) {
    if (requestAccount != null && requestAccount != customer.stellarAccount) {
      throw NotFoundException("customer for 'id' '$requestId' not found", requestId)
    }
    if (requestMemo != null && requestMemo != customer.memo) {
      throw NotFoundException("customer for 'id' '$requestId' not found", requestId)
    }
    if (requestMemoType != null && requestMemoType != customer.memoType) {
      throw NotFoundException("customer for 'id' '$requestId' not found", requestId)
    }
  }

  private fun convertCustomerToResponse(
    customer: Customer,
    type: String?,
    requiredFields: List<String>,
  ): GetCustomerResponse {
    val providedFields = mutableMapOf<String, ProvidedCustomerField>()
    val missingFields = mutableMapOf<String, CustomerField>()

    val fields =
      mapOf(
        "first_name" to createField(customer.firstName, "string", "The customer's first name"),
        "last_name" to createField(customer.lastName, "string", "The customer's last name"),
        "additional_name" to
          createField(
            customer.additionalName,
            "string",
            "The customer's additional name",
            optional = !requiredFields.contains("additional_name"),
          ),
        "address_country_code" to
          createField(
            customer.addressCountryCode,
            "string",
            "The customer's address country code",
            optional = !requiredFields.contains("address_country_code"),
          ),
        "state_or_province" to
          createField(
            customer.stateOrProvince,
            "string",
            "The customer's state or province",
            optional = !requiredFields.contains("state_or_province"),
          ),
        "city" to
          createField(
            customer.city,
            "string",
            "The customer's city",
            optional = !requiredFields.contains("city"),
          ),
        "postal_code" to
          createField(
            customer.postalCode,
            "string",
            "The customer's postal code",
            optional = !requiredFields.contains("postal_code"),
          ),
        "address" to
          createField(
            customer.address,
            "string",
            "The customer's address",
            optional = !requiredFields.contains("address"),
          ),
        "mobile_number" to
          createField(
            customer.mobileNumber,
            "string",
            "The customer's mobile number",
            optional = !requiredFields.contains("mobile_number"),
          ),
        "email_address" to
          createField(customer.emailAddress, "string", "The customer's email address"),
        "birth_date" to
          createField(
            customer.birthDate,
            "string",
            "The customer's birth date",
            optional = !requiredFields.contains("birth_date"),
          ),
        "birth_place" to
          createField(
            customer.birthPlace,
            "string",
            "The customer's birth place",
            optional = !requiredFields.contains("birth_place"),
          ),
        "birth_country_code" to
          createField(
            customer.birthCountryCode,
            "string",
            "The customer's birth country code",
            optional = !requiredFields.contains("birth_country_code"),
          ),
        "bank_name" to
          createField(
            customer.bankName,
            "string",
            "The customer's bank name",
            optional = !requiredFields.contains("bank_name"),
          ),
        "bank_account_number" to
          createField(
            customer.bankAccountNumber,
            "string",
            "The customer's bank account number",
            optional = type != "sep31-receiver" && !requiredFields.contains("bank_account_number"),
          ),
        "bank_account_type" to
          createField(
            customer.bankAccountType,
            "string",
            "The customer's bank account type",
            choices = listOf("checking", "savings"),
            optional = type != "sep31-receiver" && !requiredFields.contains("bank_account_type"),
          ),
        "bank_number" to
          createField(
            customer.bankNumber,
            "string",
            "The customer's bank routing number",
            optional = type != "sep31-receiver" && !requiredFields.contains("bank_number"),
          ),
        "bank_phone_number" to
          createField(
            customer.bankPhoneNumber,
            "string",
            "The customer's bank phone number",
            optional = !requiredFields.contains("bank_phone_number"),
          ),
        "bank_branch_number" to
          createField(
            customer.bankBranchNumber,
            "string",
            "The customer's bank branch number",
            optional = !requiredFields.contains("bank_branch_number"),
          ),
        "external_transfer_memo" to
          createField(
            customer.externalTransferMemo,
            "string",
            "The external transfer memo",
            optional = !requiredFields.contains("external_transfer_memo"),
          ),
        "clabe_number" to
          createField(
            customer.clabeNumber,
            "string",
            "The customer's CLABE number",
            optional = type != "sep31-receiver" && !requiredFields.contains("clabe_number"),
          ),
        "cbu_number" to
          createField(
            customer.cbuNumber,
            "string",
            "The customer's CBU number",
            optional = !requiredFields.contains("cbu_number"),
          ),
        "cbu_alias" to
          createField(
            customer.cbuAlias,
            "string",
            "The customer's CBU alias",
            optional = !requiredFields.contains("cbu_alias"),
          ),
        "mobile_money_number" to
          createField(
            customer.mobileMoneyNumber,
            "string",
            "The customer's mobile money number",
            optional = !requiredFields.contains("mobile_money_number"),
          ),
        "mobile_money_provider" to
          createField(
            customer.mobileMoneyProvider,
            "string",
            "The customer's mobile money provider",
            optional = !requiredFields.contains("mobile_money_provider"),
          ),
        "crypto_address" to
          createField(
            customer.cryptoAddress,
            "string",
            "The customer's crypto address",
            optional = true,
          ),
        "crypto_memo" to
          createField(
            customer.cryptoMemo,
            "string",
            "The customer's crypto memo",
            optional = !requiredFields.contains("crypto_memo"),
          ),
        "tax_id" to
          createField(
            customer.taxId,
            "string",
            "The customer's tax ID",
            optional = !requiredFields.contains("tax_id"),
          ),
        "tax_id_name" to
          createField(
            customer.taxIdName,
            "string",
            "The customer's tax ID name",
            optional = !requiredFields.contains("tax_id_name"),
          ),
        "occupation" to
          createField(
            customer.occupation,
            "string",
            "The customer's occupation",
            optional = !requiredFields.contains("occupation"),
          ),
        "employer_name" to
          createField(
            customer.employerName,
            "string",
            "The customer's employer name",
            optional = !requiredFields.contains("employer_name"),
          ),
        "employer_address" to
          createField(
            customer.employerAddress,
            "string",
            "The customer's employer address",
            optional = !requiredFields.contains("employer_address"),
          ),
        "language_code" to
          createField(
            customer.languageCode,
            "string",
            "The customer's language code",
            optional = !requiredFields.contains("language_code"),
          ),
        "id_type" to
          createField(
            customer.idType,
            "string",
            "The customer's ID type",
            optional = !requiredFields.contains("id_type"),
            choices = listOf("drivers_license", "passport", "national_id"),
          ),
        "id_country_code" to
          createField(
            customer.idCountryCode,
            "string",
            "The customer's ID country code",
            optional = !requiredFields.contains("id_country_code"),
          ),
        "id_issue_date" to
          createField(
            customer.idIssueDate,
            "string",
            "The customer's ID issue date",
            optional = !requiredFields.contains("id_issue_date"),
          ),
        "id_expiration_date" to
          createField(
            customer.idExpirationDate,
            "string",
            "The customer's ID expiration date",
            optional = !requiredFields.contains("id_expiration_date"),
          ),
        "id_number" to
          createField(
            customer.idNumber,
            "string",
            "The customer's ID number",
            optional = !requiredFields.contains("id_number"),
          ),
        "photo_id_front" to
          createField(
            customer.photoIdFront,
            "binary",
            "The customer's photo ID front",
            optional = !requiredFields.contains("photo_id_front"),
          ),
        "photo_id_back" to
          createField(
            customer.photoIdBack,
            "binary",
            "The customer's photo ID back",
            optional = !requiredFields.contains("photo_id_back"),
          ),
        "notary_approval_of_photo_id" to
          createField(
            customer.notaryApprovalOfPhotoId,
            "binary",
            "The customer's notary approval of photo ID",
            optional = !requiredFields.contains("notary_approval_of_photo_id"),
          ),
        "ip_address" to
          createField(
            customer.ipAddress,
            "string",
            "The customer's IP address",
            optional = !requiredFields.contains("ip_address"),
          ),
        "photo_proof_residence" to
          createField(
            customer.photoProofResidence,
            "binary",
            "The customer's proof of residence",
            optional = !requiredFields.contains("photo_proof_residence"),
          ),
        "sex" to
          createField(
            customer.sex,
            "string",
            "The customer's sex",
            optional = !requiredFields.contains("sex"),
          ),
        "photo_proof_of_income" to
          createField(
            customer.photoProofOfIncome,
            "binary",
            "The customer's proof of income",
            optional = !requiredFields.contains("photo_proof_of_income"),
          ),
        "proof_of_liveness" to
          createField(
            customer.proofOfLiveness,
            "binary",
            "The customer's proof of liveness",
            optional = !requiredFields.contains("proof_of_liveness"),
          ),
        "referral_id" to
          createField(
            customer.referralId,
            "string",
            "The customer's referral ID",
            optional = !requiredFields.contains("referral_id"),
          ),
      )

    // Extract fields from customer
    fields.forEach(
      fun(entry: Map.Entry<String, Field>) {
        when (entry.value) {
          is Field.Provided -> providedFields[entry.key] = (entry.value as Field.Provided).field
          is Field.Missing -> missingFields[entry.key] = (entry.value as Field.Missing).field
        }
      }
    )

    val status =
      when {
        missingFields.filter { !it.value.optional }.isNotEmpty() -> Sep12Status.NEEDS_INFO
        else -> Sep12Status.ACCEPTED
      }.toString()

    return GetCustomerResponse.builder()
      .id(customer.id)
      .status(status)
      .providedFields(providedFields)
      .fields(missingFields)
      .build()
  }

  sealed class Field {
    class Provided(val field: ProvidedCustomerField) : Field()

    class Missing(val field: CustomerField) : Field()
  }

  private fun createField(
    value: Any?,
    type: String,
    description: String,
    optional: Boolean? = false,
    choices: List<String>? = listOf(),
  ): Field {
    return when (value != null) {
      true -> {
        var builder =
          ProvidedCustomerField.builder()
            .type(type)
            .description(description)
            .status(ProvidedFieldStatus.ACCEPTED.toString())
            .optional(optional)
        if (choices != null) {
          builder = builder.choices(choices)
        }
        Field.Provided(builder.build())
      }
      false -> {
        var builder = CustomerField.builder().type(type).description(description).optional(optional)
        if (choices != null) {
          builder = builder.choices(choices)
        }
        Field.Missing(builder.build())
      }
    }
  }
}
