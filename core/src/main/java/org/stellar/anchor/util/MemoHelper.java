package org.stellar.anchor.util;

import static org.stellar.anchor.util.StringHelper.isEmpty;
import static org.stellar.sdk.xdr.MemoType.*;

import java.util.Base64;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.api.exception.SepValidationException;
import org.stellar.sdk.*;
import org.stellar.sdk.Memo;
import org.stellar.sdk.xdr.*;

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
    return memoTypeAsString(memoType(memo));
  }

  public static MemoType memoType(Memo memo) {
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
    if (isEmpty(memo)) {
      return null;
    }

    if (isEmpty(memoType)) {
      throw new SepValidationException("memo_type is required if memo is specified");
    }

    switch (memoType) {
      case "text":
        return makeMemo(memo, MemoType.MEMO_TEXT);
      case "id":
        return makeMemo(memo, MemoType.MEMO_ID);
      case "hash":
        return makeMemo(memo, MemoType.MEMO_HASH);
      case "none":
        return makeMemo(memo, MemoType.MEMO_NONE);
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
        case MEMO_RETURN:
          return new MemoReturnHash(convertBase64ToHex(memo));
        case MEMO_NONE:
          if (isEmpty(memo)) {
            return new MemoNone();
          }
          throw new SepException("memo must be empty when memoType is none");
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
    switch (memoType(memo)) {
      case MEMO_ID:
        return String.valueOf(((MemoId) memo).getId());
      case MEMO_TEXT:
        return ((MemoText) memo).getText();
      case MEMO_HASH:
        return ((MemoHash) memo).getHexValue();
      case MEMO_RETURN:
        return ((MemoReturnHash) memo).getHexValue();
      case MEMO_NONE:
        return "";
      default:
        String memoTypeStr = memoTypeAsString(memo);
        throw new SepException("Unsupported value: " + memoTypeStr);
    }
  }

  public static org.stellar.sdk.xdr.Memo toXdr(Memo memo) {
    org.stellar.sdk.xdr.Memo memoXdr = new org.stellar.sdk.xdr.Memo();
    if (memo == null || memo instanceof MemoNone) {
      memoXdr.setDiscriminant(MemoType.MEMO_NONE);
      return memoXdr;
    } else if (memo instanceof MemoId) {
      memoXdr.setDiscriminant(MemoType.MEMO_ID);
      Uint64 idXdr = new Uint64(new XdrUnsignedHyperInteger(((MemoId) memo).getId()));
      memoXdr.setId(idXdr);
      return memoXdr;
    } else if (memo instanceof MemoText) {
      memoXdr.setDiscriminant(MemoType.MEMO_TEXT);
      memoXdr.setText(new XdrString(((MemoText) memo).getText()));
      return memoXdr;
    } else if (memo instanceof MemoHash) {
      memoXdr.setDiscriminant(MemoType.MEMO_HASH);
      Hash hash = new Hash();
      hash.setHash(((MemoHash) memo).getBytes());
      memoXdr.setHash(hash);
      return memoXdr;
    } else {
      throw new IllegalArgumentException(memo.toString());
    }
  }
}
