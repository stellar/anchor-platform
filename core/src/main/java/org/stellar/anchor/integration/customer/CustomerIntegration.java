package org.stellar.anchor.integration.customer;

import org.stellar.anchor.dto.sep12.DeleteCustomerRequest;
import org.stellar.anchor.dto.sep12.GetCustomerRequest;
import org.stellar.anchor.dto.sep12.GetCustomerResponse;
import org.stellar.anchor.dto.sep12.PutCustomerRequest;
import org.stellar.anchor.dto.sep12.PutCustomerResponse;
import org.stellar.anchor.exception.AnchorException;

public interface CustomerIntegration {
  /**
   * Gets a customer.
   *
   * @param request The request to get a customer.
   * @return The GET customer response.
   */
  GetCustomerResponse getCustomer(GetCustomerRequest request) throws AnchorException;

  /**
   * Puts a customer
   *
   * @param request The request to upload a customer.
   * @return The PUT customer response.
   */
  PutCustomerResponse putCustomer(PutCustomerRequest request) throws AnchorException;

  /**
   * Deletes a customer.
   *
   * @param request The request to delete a customer.
   */
  void deleteCustomer(DeleteCustomerRequest request) throws AnchorException;

  /**
   * The request for verification.
   *
   * @param request The PUT request of a customer.
   * @return The response.
   */
  PutCustomerVerificationResponse putVerification(PutCustomerVerificationRequest request)
      throws AnchorException;
}
