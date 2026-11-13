package org.stellar.anchor.api.sep.sep12;

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;
import org.stellar.anchor.config.PII;

/**
 * The request body of the PUT /customer endpoint of SEP-12.
 *
 * @see <a
 *     href="https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0012.md#fields">Refer
 *     to SEP-12</a>
 */
@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
public class Sep12PutCustomerRequest extends Sep12CustomerRequestBase {
  @PII
  @SerializedName("first_name")
  String firstName;

  @PII
  @SerializedName("last_name")
  String lastName;

  @PII
  @SerializedName("additional_name")
  String additionalName;

  @PII
  @SerializedName("address_country_code")
  String addressCountryCode;

  @PII
  @SerializedName("state_or_province")
  String stateOrProvince;

  @PII String city;

  @PII
  @SerializedName("postal_code")
  String postalCode;

  @PII String address;

  @PII
  @SerializedName("mobile_number")
  String mobileNumber;

  @PII
  @SerializedName("email_address")
  String emailAddress;

  @PII
  @SerializedName("birth_date")
  String birthDate;

  @PII
  @SerializedName("birth_place")
  String birthPlace;

  @PII
  @SerializedName("birth_country_code")
  String birthCountryCode;

  @PII
  @SerializedName("bank_name")
  String bankName;

  @PII
  @SerializedName("bank_account_number")
  String bankAccountNumber;

  @PII
  @SerializedName("bank_account_type")
  String bankAccountType;

  @PII
  @SerializedName("bank_number")
  String bankNumber;

  @PII
  @SerializedName("bank_phone_number")
  String bankPhoneNumber;

  @PII
  @SerializedName("bank_branch_number")
  String bankBranchNumber;

  @PII
  @SerializedName("external_transfer_memo")
  String externalTransferMemo;

  @PII
  @SerializedName("clabe_number")
  String clabeNumber;

  @PII
  @SerializedName("cbu_number")
  String cbuNumber;

  @PII
  @SerializedName("cbu_alias")
  String cbuAlias;

  @PII
  @SerializedName("mobile_money_number")
  String mobileMoneyNumber;

  @PII
  @SerializedName("mobile_money_provider")
  String mobileMoneyProvider;

  @PII
  @SerializedName("crypto_address")
  String cryptoAddress;

  @Deprecated
  @PII
  @SerializedName("crypto_memo")
  String cryptoMemo;

  @PII
  @SerializedName("tax_id")
  String taxId;

  @SerializedName("tax_id_name")
  String taxIdName;

  @PII String occupation;

  @PII
  @SerializedName("employer_name")
  String employerName;

  @PII
  @SerializedName("employer_address")
  String employerAddress;

  @SerializedName("language_code")
  String languageCode;

  @SerializedName("id_type")
  String idType;

  @SerializedName("id_country_code")
  String idCountryCode;

  @SerializedName("id_issue_date")
  String idIssueDate;

  @SerializedName("id_expiration_date")
  String idExpirationDate;

  @PII
  @SerializedName("id_number")
  String idNumber;

  @PII
  @SerializedName("photo_id_front")
  byte[] photoIdFront;

  @PII
  @SerializedName("photo_id_back")
  byte[] photoIdBack;

  @PII
  @SerializedName("notary_approval_of_photo_id")
  byte[] notaryApprovalOfPhotoId;

  @PII
  @SerializedName("ip_address")
  String ipAddress;

  @PII
  @SerializedName("photo_proof_residence")
  byte[] photoProofResidence;

  @PII String sex;

  @PII
  @SerializedName("photo_proof_of_income")
  byte[] photoProofOfIncome;

  @PII
  @SerializedName("proof_of_liveness")
  byte[] proofOfLiveness;

  @SerializedName("referral_id")
  String referralId;

  @PII
  @SerializedName("mobile_number_verification")
  String mobileNumberVerification;

  @PII
  @SerializedName("email_address_verification")
  String emailAddressVerification;
}
