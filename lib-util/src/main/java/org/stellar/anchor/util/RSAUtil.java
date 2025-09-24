package org.stellar.anchor.util;

import io.jsonwebtoken.SignatureAlgorithm;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.apache.commons.lang3.StringUtils;

public class RSAUtil {

  public static final String BEGIN_PUBLIC_KEY = "-----BEGIN PUBLIC KEY-----";
  public static final String END_PUBLIC_KEY = "-----END PUBLIC KEY-----";
  public static final String BEGIN_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----";
  public static final String END_PRIVATE_KEY = "-----END PRIVATE KEY-----";

  public static final String RSA_ALGORITHM = SignatureAlgorithm.RS512.getFamilyName();
  public static final String SHA512_WITH_RSA_ALGORITHM = SignatureAlgorithm.RS512.getJcaName();

  public static PublicKey generatePublicKey(String publicKey)
      throws NoSuchAlgorithmException, InvalidKeySpecException {
    return generatePublicKey(publicKey, RSA_ALGORITHM);
  }

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

  public static PrivateKey generatePrivateKey(String privateKey)
      throws NoSuchAlgorithmException, InvalidKeySpecException {
    return generatePrivateKey(privateKey, RSA_ALGORITHM);
  }

  /**
   * Generate a private key from a provided string.
   *
   * @param privateKey private key in a String format
   * @param keyFactoryAlgorithm the name of the requested key algorithm
   * @return the public key object
   * @throws NoSuchAlgorithmException – if no Provider supports a KeyFactorySpi implementation for
   *     the specified algorithm
   * @throws InvalidKeySpecException – if the given key specification is inappropriate for this key
   *     factory to produce a private key.
   */
  public static PrivateKey generatePrivateKey(String privateKey, String keyFactoryAlgorithm)
      throws NoSuchAlgorithmException, InvalidKeySpecException {
    privateKey =
        privateKey
            .replace(BEGIN_PRIVATE_KEY, StringUtils.EMPTY)
            .replaceAll(StringUtils.CR, StringUtils.EMPTY)
            .replaceAll(StringUtils.LF, StringUtils.EMPTY)
            .replace(END_PRIVATE_KEY, StringUtils.EMPTY);

    byte[] keyBytes = Base64.getDecoder().decode(privateKey);
    PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
    KeyFactory kf = KeyFactory.getInstance(keyFactoryAlgorithm);

    return kf.generatePrivate(keySpec);
  }

  public static boolean isValidPublicKey(String publicKey, String keyFactoryAlgorithm) {
    try {
      generatePublicKey(publicKey, keyFactoryAlgorithm);
    } catch (Exception e) {
      return false;
    }

    return true;
  }

  public static boolean isValidPrivateKey(String privateKey, String keyFactoryAlgorithm) {
    try {
      generatePrivateKey(privateKey, keyFactoryAlgorithm);
    } catch (Exception e) {
      return false;
    }

    return true;
  }

  public static boolean isValidSignature(String signature, String dataString, PublicKey publicKey)
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException {
    return isValidSignature(signature, dataString, publicKey, SHA512_WITH_RSA_ALGORITHM);
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

    try {
      return sign.verify(Base64.getDecoder().decode(signature));
    } catch (SignatureException ignored) {
      return false;
    }
  }

  /**
   * Sign dataString.
   *
   * @param dataString The data to sign
   * @param privateKey The private key to sign with
   * @return The Base64 encoded signature
   */
  public static String sign(String dataString, PrivateKey privateKey)
      throws NoSuchAlgorithmException, SignatureException, InvalidKeyException {
    return sign(dataString, privateKey, SHA512_WITH_RSA_ALGORITHM);
  }

  public static String sign(String dataString, PrivateKey privateKey, String signatureAlgorithm)
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException {
    if (privateKey == null) {
      throw new IllegalArgumentException("Private key is null");
    }

    Signature sign = Signature.getInstance(signatureAlgorithm);
    sign.initSign(privateKey);
    sign.update(dataString.getBytes());

    return new String(Base64.getEncoder().encode(sign.sign()));
  }
}
