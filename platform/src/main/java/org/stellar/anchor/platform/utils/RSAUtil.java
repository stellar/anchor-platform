package org.stellar.anchor.platform.utils;

import com.google.common.io.BaseEncoding;
import io.jsonwebtoken.SignatureAlgorithm;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.apache.commons.lang3.StringUtils;

public class RSAUtil {
  public static final String BEGIN_PUBLIC_KEY = "-----BEGIN PUBLIC KEY-----";
  public static final String END_PUBLIC_KEY = "-----END PUBLIC KEY-----";

  public static final String RSA_ALGORITHM = SignatureAlgorithm.RS512.getFamilyName();
  public static final String SHA512_WITH_RSA_ALGORITHM = SignatureAlgorithm.RS512.getJcaName();

  /**
   * Generate a public key from a provided string.
   *
   * @param publicKey public key in a String format
   * @param keyFactoryAlgorithm the name of the requested key algorithm
   * @return the public key object
   * @throws NoSuchAlgorithmException – if no Provider supports a KeyFactorySpi implementation for
   *     the specified algorithm
   * @throws InvalidKeySpecException – if the given key specification is inappropriate for this key
   *     factory to produce a public key.
   */
  public static PublicKey generatePublicKey(String publicKey, String keyFactoryAlgorithm)
      throws NoSuchAlgorithmException, InvalidKeySpecException {

    publicKey =
        publicKey
            .replace(BEGIN_PUBLIC_KEY, StringUtils.EMPTY)
            .replaceAll(StringUtils.CR, StringUtils.EMPTY)
            .replaceAll(StringUtils.LF, StringUtils.EMPTY)
            .replace(END_PUBLIC_KEY, StringUtils.EMPTY);

    byte[] keyBytes = Base64.getDecoder().decode(publicKey);
    X509EncodedKeySpec X509publicKey = new X509EncodedKeySpec(keyBytes);
    KeyFactory kf = KeyFactory.getInstance(keyFactoryAlgorithm);

    return kf.generatePublic(X509publicKey);
  }

  public static boolean isValidPublicKey(String publicKey, String keyFactoryAlgorithm) {
    try {
      generatePublicKey(publicKey, keyFactoryAlgorithm);
    } catch (Exception e) {
      return false;
    }

    return true;
  }

  /**
   * Verify signature
   *
   * @param signature signature to verify
   * @param dataString data to verify
   * @param publicKey public key
   * @param signatureAlgorithm the standard name of the algorithm requested
   * @return true if signature is valid; false otherwise
   */
  public static boolean isValidSignature(
      String signature, String dataString, PublicKey publicKey, String signatureAlgorithm)
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException {
    if (publicKey == null) {
      throw new IllegalArgumentException("Public key is null");
    }

    Signature sign = Signature.getInstance(signatureAlgorithm);
    sign.initVerify(publicKey);
    sign.update(dataString.getBytes());

    return sign.verify(BaseEncoding.base64().decode(signature));
  }
}
