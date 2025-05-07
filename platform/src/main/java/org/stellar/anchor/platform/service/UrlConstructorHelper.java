package org.stellar.anchor.platform.service;

import static org.stellar.anchor.util.StringHelper.*;

import java.util.List;
import java.util.Map;
import org.apache.commons.beanutils.BeanUtils;
import org.stellar.anchor.SepTransaction;
import org.stellar.anchor.api.platform.GetTransactionResponse;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.platform.data.JdbcSepTransaction;
import org.stellar.anchor.platform.utils.PlatformTransactionHelper;
import org.stellar.anchor.sep24.Sep24Transaction;
import org.stellar.anchor.util.StringHelper;

public class UrlConstructorHelper {
  /**
   * Add fields from the transaction to the data map. The fields in the extractingFields are
   * extracted from txn and added to the data.
   *
   * @param assetService the asset service
   * @param data the data map
   * @param txn the JdbcSep6Transaction or JdbcSep24Transaction
   * @param txnFields the fields to extract from txn
   */
  public static void addTxnFields(
      AssetService assetService,
      Map<String, String> data,
      SepTransaction txn,
      List<String> txnFields) {
    if (txn instanceof JdbcSepTransaction) {
      // if the txn is not a JbcSepTransaction, we can't extract the fields
      GetTransactionResponse platformTxn =
          PlatformTransactionHelper.toGetTransactionResponse(
              (JdbcSepTransaction) txn, assetService);

      for (String field : txnFields) {
        try {
          field = camelToSnake(field);
          String value = BeanUtils.getProperty(platformTxn, snakeToCamelCase(field));
          if (!StringHelper.isEmpty((value))) {
            data.put(field, value);
          }
        } catch (Exception e) {
          // give up. no need to add the field
        }
      }
    }
  }

  public static String getAccount(Sep24Transaction txn) {
    return isEmpty(txn.getWebAuthAccountMemo())
        ? txn.getWebAuthAccount()
        : txn.getWebAuthAccount() + ":" + txn.getWebAuthAccountMemo();
  }

  public static String getAccount(String webAuthAccount, String webAuthAccountMemo) {
    return isEmpty(webAuthAccountMemo) ? webAuthAccount : webAuthAccount + ":" + webAuthAccountMemo;
  }
}
