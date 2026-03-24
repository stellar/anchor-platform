package org.stellar.anchor.util;

import static org.stellar.sdk.xdr.MemoType.MEMO_HASH;

import org.stellar.anchor.config.CustodyConfig.CustodyType;

public class CustodyUtils {

  public static boolean isMemoTypeSupported(CustodyType custodyType, String memoType) {
    switch (custodyType) {
      case FIREBLOCKS:
        return !MemoHelper.memoTypeAsString(MEMO_HASH).equals(memoType);
      default:
        return true;
    }
  }
}
