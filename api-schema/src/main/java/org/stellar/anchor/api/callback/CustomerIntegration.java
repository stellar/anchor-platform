package org.stellar.anchor.api.callback;

import org.stellar.anchor.api.exception.AnchorException;

/**
 * The interface for the customer endpoint of the callback API.
 *
 * @see <a
 *     href="https://github.com/stellar/stellar-docs/blob/main/openapi/anchor-platform/Callbacks%20API.yml">Callback
 *     API</a>
 */
public interface CustomerIntegration {
  /**
   * Gets a customer.
   *
   * @param request The request to get a customer.
   * @return The GET customer response.
   * @throws AnchorException if error happens
   */
  GetCustomerResponse getCustomer(GetCustomerRequest request) throws AnchorException;

  /**
   * Puts a customer
   *
   * @param request The request to upload a customer.
   * @return The PUT customer response.
   * @throws AnchorException if error happens
   */
  PutCustomerResponse putCustomer(PutCustomerRequest request) throws AnchorException;

  /**
   * Deletes a customer.
   *
   * @param id The id of the customer to be deleted.
   * @throws AnchorException if error happens
   */
  void deleteCustomer(String id) throws AnchorException;
}
