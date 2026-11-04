package org.stellar.anchor.util;

import static org.stellar.anchor.util.StringHelper.isEmpty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/**
 * Secrets this repository once published (the fake Helm secret store and example files). They are
 * public, so a deployment configured with one of them is not protected. The values are held here
 * only as SHA-256 hex digests, so this class does not republish them.
 */
public final class PublishedSecrets {
  /** JWT and API secrets that the Helm secret store used to commit. */
  private static final Set<String> SECRET_DIGESTS =
      Set.of(
          "a1c53f314bf585b62429f533e8dbb321c6a3e30e2c002ce7608b5a5725b0c8ef", // SEP10_JWT_SECRET
          "9eb3785f58869587bb944b7d5430ad43c0eb705bec07143f99136109585975b0", // SEP24_INTERACTIVE_URL_JWT_SECRET
          "d26bb34cc86832d3224245907840876a9a969993d63253c84cc499d9320d8012", // SEP24_MORE_INFO_URL_JWT_SECRET
          "49e3cdfe90cd5e1bfc1da2e52584140c9ed8dfbecc75a66158e91f0592fc3e2f", // SEP6_MORE_INFO_URL_JWT_SECRET
          "f93e773a258efefaf5c187ba106b5a0457dc35cd8e0867c64a21bafa7bd14b9d", // SEP45_JWT_SECRET
          "daaa11a5acca6359ce78470a840d96993e20666cbd125c98b26c407483c913d0", // CALLBACK_API_AUTH_SECRET
          "bbb33d10052010e98d247ab981d0bac255718ee69ce6c76c01f53fa70d0371c2"); // PLATFORM_API_AUTH_SECRET

  /** SEP-10 signing seeds committed in the default test profile and in the k8s example. */
  private static final Set<String> SEED_DIGESTS =
      Set.of(
          "49633fc2f64454b3446a2601db22fe83a552c7e6a183e37d9fea112d672daec2", // default profile
          // seed
          "245d518f134549de52b66c56b785a1b5172eaa999f6530536337b03c5162809e"); // k8s example seed

  private PublishedSecrets() {}

  public static boolean isPublishedSecret(String value) {
    return !isEmpty(value) && SECRET_DIGESTS.contains(sha256Hex(value));
  }

  public static boolean isPublishedSigningSeed(String value) {
    return !isEmpty(value) && SEED_DIGESTS.contains(sha256Hex(value));
  }

  static Set<String> secretDigests() {
    return SECRET_DIGESTS;
  }

  static Set<String> seedDigests() {
    return SEED_DIGESTS;
  }

  static String sha256Hex(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
