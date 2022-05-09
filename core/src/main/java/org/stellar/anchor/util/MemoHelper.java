package org.stellar.anchor.util;

import static org.stellar.sdk.xdr.MemoType.*;

import java.util.Base64;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.api.exception.SepValidationException;
import org.stellar.sdk.*;
import org.stellar.sdk.xdr.MemoType;

public class MemoHelper {
  public static String memoTypeAsString(MemoType memoType) {
    switch (memoType) {
      case MEMO_ID:
        return "id";
      case MEMO_TEXT:
        return "text";
      case MEMO_HASH:
        return "hash";
      case MEMO_NONE:
        return "none";
      case MEMO_RETURN:
        return "return";
      default:
        throw new RuntimeException("Unsupported value: " + memoType);
    }
  }

  public static String memoTypeAsString(Memo memo) {
    return memoTypeAsString(getMemoType(memo));
  }

  private static MemoType getMemoType(Memo memo) {
    if (memo == null || memo instanceof MemoNone) {
      return MEMO_NONE;
    } else if (memo instanceof MemoId) {
      return MEMO_ID;
    } else if (memo instanceof MemoText) {
      return MEMO_TEXT;
    } else if (memo instanceof MemoHash) {
      return MEMO_HASH;
    }

    return MEMO_RETURN;
  }

  public static Memo makeMemo(String memo, String memoType) throws SepException {
    if (memo == null || memoType == null) {
      return null;
    }

    switch (memoType) {
      case "text":
        return makeMemo(memo, MemoType.MEMO_TEXT);
      case "id":
        return makeMemo(memo, MemoType.MEMO_ID);
      case "hash":
        return makeMemo(memo, MemoType.MEMO_HASH);
      case "none":
      case "return":
        throw new SepException("Unsupported value: " + memoType);
      default:
        throw new SepValidationException(String.format("Invalid memo type: %s", memoType));
    }
  }

  public static Memo makeMemo(String memo, MemoType memoType) throws SepException {
    try {
      switch (memoType) {
        case MEMO_ID:
          return new MemoId(Long.parseLong(memo));
        case MEMO_TEXT:
          return new MemoText(memo);
        case MEMO_HASH:
          return new MemoHash(convertBase64ToHex(memo));
        default:
          throw new SepException("Unsupported value: " + memoType);
      }
    } catch (NumberFormatException nfex) {
      throw new SepValidationException(
          String.format("Invalid memo %s of type:%s", memo, memoType), nfex);
    }
  }

  public static String convertBase64ToHex(String memo) {
    return Hex.encodeHexString(Base64.getDecoder().decode(memo.getBytes()));
  }

  public static String convertHexToBase64(String memo) throws DecoderException {
    return Base64.getEncoder().encodeToString(Hex.decodeHex(memo));
  }

  public static String memoAsString(Memo memo) throws SepException {
    if (memo == null) {
      return null;
    }
    switch (getMemoType(memo)) {
      case MEMO_ID:
        return String.valueOf(((MemoId) memo).getId());

      case MEMO_TEXT:
        return ((MemoText) memo).getText();

      case MEMO_HASH:
        return ((MemoHash) memo).getHexValue();

      default:
        String memoTypeStr = memoTypeAsString(memo);
        throw new SepException("Unsupported value: " + memoTypeStr);
    }
  }
}
