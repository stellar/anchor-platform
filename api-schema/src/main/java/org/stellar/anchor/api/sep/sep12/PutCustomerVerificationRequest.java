package org.stellar.anchor.api.sep.sep12;

import java.util.List;
import lombok.Data;

@Data
public class PutCustomerVerificationRequest {
  String id;
  List<String> sep9FieldsWithVerficication;
}
