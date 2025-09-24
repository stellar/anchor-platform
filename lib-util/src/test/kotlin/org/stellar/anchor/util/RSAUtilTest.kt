package org.stellar.anchor.util

import com.google.common.io.BaseEncoding
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.charset.Charset
import java.security.*
import java.security.spec.InvalidKeySpecException
import java.util.*
import kotlin.test.assertNotNull
import org.apache.commons.lang3.StringUtils
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.api.exception.SepNotFoundException
import org.stellar.anchor.util.FileUtil.getResourceFileAsString
import org.stellar.anchor.util.RSAUtil.*

class RSAUtilTest {
  private val privateKeyString =
    "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"

  private val publicKeyString =
    "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAhoo3nmoSLyUPgEO1VZ6eosI9YzwzP+HG9+UeoldzvD1ZrYjdh5Uz/fNBDzNXlu0DlYzXk6uBGOJFtVzd5HL2iF5MZ5KsTWbMam3o4FNSL8kPDkqcbLTU5CmUG6BXsZkC9L2X4QeC2LmJhUfIvxKgTFzwGfPMWrPdPFDEZPVslHl+wTfEQN+OOKEVlLgPINImSqmFEhL9YzGIng+X3BJeq1k33lFVNTm+5KIPqSVNzYmkRtBZ7eKpaAd5p9VaiceB+zhD42yiju9+GK2+z64bDZvUu+quSAhGtyXyqraqqSDUFIdTubZHG/pUumX830de0UvqM8ZhZGSAsyRuendqX6mPPa5Z1lSZKb7k5Xj4IZh/6aN+l10zNzWGhoQ3SmHRf2mxbyDvP+QRgCO7wf/NE3tikcWwa2r63fQNhhXZ2qw9/pxIgkhk5CBwQ92URfcnsfquJLeZ+xfG6ke9JffBnebnlor6+Qk1tJXy6x6ba99iaIhO//bd+xQgL3BU5nlas/bp8uw2YmURSxr3iMQg+UxyCR1BhFaVPBCveoHF7/WAzpa13JCtkreyX25DQ28Ua6mM5fHcma2PgY4kMaD3mhLoknnL9Jm0q883u+p25qM1ouPMMzu65nlFLW9HIGnKMsM2lcW7+VbjOAHk3QCDHyfhrCa6ze4rPDXfuwOXdusCAwEAAQ=="
  private val payload: String = "Test payload"
  private val privateKey = generatePrivateKey(privateKeyString)
  private val publicKey = generatePublicKey(publicKeyString)
  private val signature = sign(payload, privateKey)

  @Test
  fun `test generate keys`() {
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(4096)
    val pair: KeyPair = generator.generateKeyPair()

    val privateStr = Base64.getEncoder().encodeToString(pair.private.encoded)
    val publicString = Base64.getEncoder().encodeToString(pair.public.encoded)

    val privateKey = generatePrivateKey(privateStr)
    val publicKey: PublicKey = generatePublicKey(publicString)
    val sig = sign(payload, privateKey)
    val validationResult = isValidSignature(sig, payload, publicKey)
    assertTrue(validationResult)
  }

  @Test
  fun `test valid public key`() {
    assertNotNull(generatePublicKey(publicKeyString, RSA_ALGORITHM))
    assertTrue(isValidPublicKey(publicKeyString, RSA_ALGORITHM))

    val publicKeyStr2 =
      publicKeyString
        .replace(BEGIN_PUBLIC_KEY, StringUtils.EMPTY)
        .replace(END_PUBLIC_KEY, StringUtils.EMPTY)
    assertNotNull(generatePublicKey(publicKeyStr2, RSA_ALGORITHM))
    assertTrue(isValidPublicKey(publicKeyStr2, RSA_ALGORITHM))
  }

  @ParameterizedTest
  @ValueSource(strings = [""])
  fun `test empty public key`(publicKey: String) {
    assertThrows<InvalidKeySpecException> { generatePublicKey(publicKey, RSA_ALGORITHM) }
    assertFalse(isValidPublicKey(publicKey, RSA_ALGORITHM))
  }

  @ParameterizedTest
  @ValueSource(strings = ["test_public_key"])
  fun `test invalid public key`(publicKey: String) {
    val decodedPublicKey =
      BaseEncoding.base64().encode(publicKey.toByteArray(Charset.defaultCharset())).toString()
    assertThrows<InvalidKeySpecException> { generatePublicKey(decodedPublicKey, RSA_ALGORITHM) }
    assertFalse(isValidPublicKey(decodedPublicKey, RSA_ALGORITHM))
  }

  @ParameterizedTest
  @ValueSource(strings = ["test_public_key"])
  fun `test invalid public key encoding`(publicKey: String) {
    val decodedPublicKey = BaseEncoding.base32().encode(publicKey.toByteArray())
    assertThrows<InvalidKeySpecException> { generatePublicKey(decodedPublicKey, RSA_ALGORITHM) }
    assertFalse(isValidPublicKey(decodedPublicKey, RSA_ALGORITHM))
  }

  @ParameterizedTest
  @ValueSource(strings = [""])
  fun `test invalid public key algorithm`(publicKey: String) {
    assertThrows<NoSuchAlgorithmException> { generatePublicKey(publicKey, "INVALID_ALGORITHM") }
    assertFalse(isValidPublicKey(publicKey, RSA_ALGORITHM))
  }

  @Test
  fun `test isSignatureValid() returns true for valid signature, eventObject, public key and algorithm`() {
    val publicKey: PublicKey = generatePublicKey(publicKeyString, RSA_ALGORITHM)
    val sig = sign(payload, privateKey)
    val validationResult = isValidSignature(sig, payload, publicKey)
    assertTrue(validationResult)
  }

  @Test
  fun `test isSignatureValid() for invalid signature`() {
    val publicKey: PublicKey = generatePublicKey(publicKeyString, RSA_ALGORITHM)
    val invalidSignature =
      "Yww6co109EfZ6BBam0zr1ewhv2gB3sFrfzcmbEFTttGp6GYVNEOslcMIMbjrFsFtkiEIO5ogvPI7Boz7yQUiXqh92Spj1aG5NoGDdjiW2ozTJxKq7ECK9IsS5vTjIxnBXUIXokCAN2BuiyA8d7LciJ6HwzS+DIvFNyvv7uKU6O0="
    assertFalse(isValidSignature(invalidSignature, payload, publicKey))
  }

  @Test
  fun `test isSignatureValid() for invalid data`() {
    val invalidEventObject = payload + "test"
    assertFalse(isValidSignature(signature, invalidEventObject, publicKey))
  }

  @Test
  fun `test isSignatureValid() for invalid public key`() {
    val invalidPublicKey: PublicKey = generatePublicKey(invalidPublicKey, RSA_ALGORITHM)
    assertFalse(isValidSignature(signature, payload, invalidPublicKey))
  }

  @Test
  fun `test isSignatureValid() for invalid algorithm`() {
    assertThrows<NoSuchAlgorithmException> {
      isValidSignature(signature, payload, publicKey, "INVALID_ALGORITHM")
    }
  }

  @ParameterizedTest
  @ValueSource(strings = [""])
  fun `test empty private key`(privateKey: String) {
    assertThrows<InvalidKeySpecException> { generatePrivateKey(privateKey, RSA_ALGORITHM) }
    assertFalse(isValidPrivateKey(privateKey, RSA_ALGORITHM))
  }

  @ParameterizedTest
  @ValueSource(strings = ["test_public_key"])
  fun `test invalid private key`(privateKey: String) {
    val decodedPrivateKey =
      BaseEncoding.base64().encode(privateKey.toByteArray(Charset.defaultCharset())).toString()
    assertThrows<InvalidKeySpecException> { generatePrivateKey(decodedPrivateKey, RSA_ALGORITHM) }
    assertFalse(isValidPrivateKey(decodedPrivateKey, RSA_ALGORITHM))
  }

  @ParameterizedTest
  @ValueSource(strings = ["test_public_key"])
  fun `test invalid private key encoding`(privateKey: String) {
    val decodedPrivateKey = BaseEncoding.base32().encode(privateKey.toByteArray())
    assertThrows<InvalidKeySpecException> { generatePrivateKey(decodedPrivateKey, RSA_ALGORITHM) }
    assertFalse(isValidPublicKey(decodedPrivateKey, RSA_ALGORITHM))
  }

  @ParameterizedTest
  @ValueSource(strings = [""])
  fun `test invalid private key algorithm`(privateKey: String) {
    assertThrows<NoSuchAlgorithmException> { generatePrivateKey(privateKey, "INVALID_ALGORITHM") }
    assertFalse(isValidPublicKey(privateKey, RSA_ALGORITHM))
  }

  @Test
  fun `test sign and verify with random key`() {
    val kp = KeyPairGenerator.getInstance("RSA").genKeyPair()
    val signature = sign(payload, kp.private)
    assertTrue(isValidSignature(signature, payload, kp.public))
  }

  @Test
  fun `test sign and verify`() {
    val privateKey = generatePrivateKey(privateKeyString)
    val publicKey = generatePublicKey(publicKeyString)

    val signature = sign(payload, privateKey)
    assertTrue(isValidSignature(signature, payload, publicKey))
  }

  @Throws(IOException::class, SepNotFoundException::class)
  fun getCompactJsonString(fileName: String): String {
    val gson = GsonBuilder().serializeNulls().create()
    val el = JsonParser.parseString(getResourceFileAsString(fileName))
    return gson.toJson(el)
  }

  private val invalidPublicKey =
    """-----BEGIN PUBLIC KEY-----
MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCpEk+ZuHkZdeSktVTV3MoYPcpI
+3EPVueKsk4cEeAn2XFgBWLaqz8KOcaMK1/mL92rTAPBaznxvhFL6DKf1C9EKjGf
bHFsXrGLEduo7Puk6L4H2YHg+Tpyjaa7bAIs2OjZe2rhx6Qmkk1tiuyJLMmKeJxO
E+UUh8VtHq82whkX0wIDAQAB
-----END PUBLIC KEY-----"""
}
