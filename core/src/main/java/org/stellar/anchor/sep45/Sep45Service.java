package org.stellar.anchor.sep45;

import static org.stellar.anchor.util.StringHelper.isEmpty;
import static org.stellar.sdk.Auth.authorizeEntry;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import lombok.AllArgsConstructor;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.InternalServerErrorException;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.api.exception.rpc.InvalidRequestException;
import org.stellar.anchor.api.sep.sep45.ChallengeRequest;
import org.stellar.anchor.api.sep.sep45.ChallengeResponse;
import org.stellar.anchor.api.sep.sep45.ValidationRequest;
import org.stellar.anchor.api.sep.sep45.ValidationResponse;
import org.stellar.anchor.auth.JwtService;
import org.stellar.anchor.auth.WebAuthJwt;
import org.stellar.anchor.config.AppConfig;
import org.stellar.anchor.config.SecretConfig;
import org.stellar.anchor.config.Sep45Config;
import org.stellar.anchor.network.Rpc;
import org.stellar.anchor.util.ClientDomainHelper;
import org.stellar.anchor.util.Log;
import org.stellar.anchor.xdr.SorobanAuthorizationEntryList;
import org.stellar.sdk.*;
import org.stellar.sdk.Transaction;
import org.stellar.sdk.operations.InvokeHostFunctionOperation;
import org.stellar.sdk.responses.sorobanrpc.SimulateTransactionResponse;
import org.stellar.sdk.scval.Scv;
import org.stellar.sdk.xdr.*;

@AllArgsConstructor
public class Sep45Service implements ISep45Service {
  private static final String WEB_AUTH_VERIFY_FN = "web_auth_verify";
  private static final String KEY_ACCOUNT = "account";
  private static final String KEY_HOME_DOMAIN = "home_domain";
  private static final String KEY_HOME_DOMAIN_ADDRESS = "home_domain_address";
  private static final String KEY_CLIENT_DOMAIN = "client_domain";
  private static final String KEY_CLIENT_DOMAIN_ADDRESS = "client_domain_address";
  private static final String KEY_WEB_AUTH_DOMAIN = "web_auth_domain";
  private final AppConfig appConfig;
  private final SecretConfig secretConfig;
  private final Sep45Config sep45Config;
  private final Rpc rpc;
  private final JwtService jwtService;

  @Override
  public ChallengeResponse getChallenge(ChallengeRequest request) throws AnchorException {
    KeyPair signingKeypair = KeyPair.fromSecretSeed(secretConfig.getSep10SigningSeed());
    KeyPair simulatingKeypair =
        KeyPair.fromSecretSeed(secretConfig.getSep45SimulatingSigningSeed());
    Network network = new Network(appConfig.getStellarNetworkPassphrase());

    SCVal[] args = createArgsFromRequest(request);

    // Simulate the transaction in recording mode to get the authorization entries
    TransactionBuilderAccount source = rpc.getAccount(simulatingKeypair.getAccountId());
    InvokeHostFunctionOperation operation =
        InvokeHostFunctionOperation.invokeContractFunctionOperationBuilder(
                sep45Config.getWebAuthContractId(), WEB_AUTH_VERIFY_FN, Arrays.asList(args))
            .sourceAccount(source.getAccountId())
            .build();

    Transaction transaction =
        new TransactionBuilder(source, network)
            .setBaseFee(Transaction.MIN_BASE_FEE)
            .addOperation(operation)
            .setTimeout(300)
            .build();

    SimulateTransactionResponse simulateTransactionResponse = rpc.simulateTransaction(transaction);

    List<SorobanAuthorizationEntry> authEntries = new ArrayList<>();
    if (simulateTransactionResponse.getError() != null) {
      throw new InternalServerErrorException("Failed to simulate transaction");
    } else {

      // Find and sign the authorization entry belonging to the server
      for (String xdr : simulateTransactionResponse.getResults().get(0).getAuth()) {
        try {
          SorobanAuthorizationEntry entry = SorobanAuthorizationEntry.fromXdrBase64(xdr);
          if (hasAccountCredentials(entry) && matchesKeypairAccount(entry, signingKeypair)) {
            long sequenceNumber = rpc.getLatestLedger().getSequence().longValue();
            entry = authorizeEntry(xdr, signingKeypair, sequenceNumber + 10, network);
          }
          authEntries.add(entry);
        } catch (IOException e) {
          throw new InternalServerErrorException("Failed to decode auth xdr");
        }
      }
    }

    try {
      String authEntriesXdr =
          new SorobanAuthorizationEntryList(authEntries.toArray(SorobanAuthorizationEntry[]::new))
              .toXdrBase64();

      return ChallengeResponse.builder()
          .authorizationEntries(authEntriesXdr)
          .networkPassphrase(rpc.getRpc().getNetwork().getPassphrase())
          .build();
    } catch (IOException e) {
      throw new InternalServerErrorException("Failed to encode auth entries");
    }
  }

  /**
   * Creates the arguments for the web_auth_verify function.
   *
   * @param request the challenge request
   * @return the arguments
   * @throws SepException if the client domain is invalid
   */
  private SCVal[] createArgsFromRequest(ChallengeRequest request) throws SepException {
    LinkedHashMap<String, String> argsMap = new LinkedHashMap<>();
    argsMap.put(KEY_ACCOUNT, request.getAccount());
    argsMap.put(KEY_HOME_DOMAIN, request.getHomeDomain());
    argsMap.put(
        KEY_HOME_DOMAIN_ADDRESS,
        KeyPair.fromSecretSeed(secretConfig.getSep10SigningSeed()).getAccountId());
    argsMap.put(KEY_WEB_AUTH_DOMAIN, sep45Config.getWebAuthDomain());
    if (!isEmpty(request.getClientDomain())) {
      String clientDomainSigner =
          ClientDomainHelper.fetchSigningKeyFromClientDomain(request.getClientDomain(), false);
      argsMap.put(KEY_CLIENT_DOMAIN, request.getClientDomain());
      argsMap.put(KEY_CLIENT_DOMAIN_ADDRESS, clientDomainSigner);
    }
    return createArguments(argsMap);
  }

  /**
   * Transactions a map of arguments to a SCVal.
   *
   * @param args the map of arguments
   * @return the SCVal
   */
  private SCVal[] createArguments(LinkedHashMap<String, String> args) {
    SCMapEntry[] entries =
        args.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(
                entry ->
                    SCMapEntry.builder()
                        .key(Scv.toSymbol(entry.getKey()))
                        .val(Scv.toString(entry.getValue()))
                        .build())
            .toArray(SCMapEntry[]::new);
    SCMap scMap = new SCMap(entries);

    return new SCVal[] {SCVal.builder().discriminant(SCValType.SCV_MAP).map(scMap).build()};
  }

  @Override
  public ValidationResponse validate(ValidationRequest request) throws AnchorException {
    KeyPair signingKeypair = KeyPair.fromSecretSeed(secretConfig.getSep10SigningSeed());
    KeyPair simulatingKeypair =
        KeyPair.fromSecretSeed(secretConfig.getSep45SimulatingSigningSeed());
    Network network = new Network(appConfig.getStellarNetworkPassphrase());

    SorobanAuthorizationEntryList authEntries;
    try {
      authEntries = SorobanAuthorizationEntryList.fromXdrBase64(request.getAuthorizationEntries());
    } catch (IOException e) {
      throw new BadRequestException("Failed to decode auth entries");
    }

    // Verify that all entries have the same arguments and that the arguments are valid
    SCVal[] firstEntryArgs = {};
    for (SorobanAuthorizationEntry entry : authEntries.getAuthorizationEntryList()) {
      if (firstEntryArgs.length == 0) {
        firstEntryArgs = entry.getRootInvocation().getFunction().getContractFn().getArgs();
        verifyArguments(firstEntryArgs[0].getMap().getSCMap());
      } else {
        SCVal[] entryArgs = entry.getRootInvocation().getFunction().getContractFn().getArgs();
        if (!Arrays.equals(firstEntryArgs, entryArgs)) {
          throw new BadRequestException("Mismatched arguments in authorization entry");
        }
      }

      // If the entry is signed by the server, verify the signature
      if (hasAccountCredentials(entry) && matchesKeypairAccount(entry, signingKeypair)) {
        verifyServerSignature(entry, signingKeypair);
      }
    }

    // Simulate the transaction in enforcing mode to check the authorization entry credentials
    TransactionBuilderAccount source = rpc.getAccount(simulatingKeypair.getAccountId());

    InvokeHostFunctionOperation operation =
        InvokeHostFunctionOperation.invokeContractFunctionOperationBuilder(
                sep45Config.getWebAuthContractId(),
                WEB_AUTH_VERIFY_FN,
                Arrays.asList(firstEntryArgs))
            .sourceAccount(source.getAccountId())
            .auth(Arrays.asList(authEntries.getAuthorizationEntryList()))
            .build();

    Transaction transaction =
        new TransactionBuilder(source, network)
            .setBaseFee(Transaction.MIN_BASE_FEE)
            .addOperation(operation)
            .setTimeout(300)
            .build();

    SimulateTransactionResponse simulateTransactionResponse = rpc.simulateTransaction(transaction);
    if (simulateTransactionResponse.getError() != null) {
      throw new InvalidRequestException("Failed to simulate transaction");
    }

    // Construct the JWT token
    Map<String, String> argsMap = extractArgs(firstEntryArgs[0].getMap().getSCMap());

    long issuedAt = Instant.now().getEpochSecond();
    String account = argsMap.get(KEY_ACCOUNT);
    String homeDomain = argsMap.get(KEY_HOME_DOMAIN);
    String clientDomain = argsMap.get(KEY_CLIENT_DOMAIN);

    String authUrl = "http://" + homeDomain + "/sep45/auth";
    String hashHex;
    try {
      hashHex =
          Util.bytesToHex(
              Util.hash(
                  authEntries.getAuthorizationEntryList()[0].getRootInvocation().toXdrByteArray()));
    } catch (IOException e) {
      throw new InternalServerErrorException("Unable to decode invocation");
    }

    WebAuthJwt jwt =
        WebAuthJwt.of(
            authUrl,
            account,
            issuedAt,
            issuedAt + sep45Config.getJwtTimeout(),
            hashHex,
            clientDomain,
            homeDomain);

    return ValidationResponse.builder().token(jwtService.encode(jwt)).build();
  }

  /**
   * Verifies an auth entry has been signed by a particular keypair.
   *
   * @param entry entry to verify
   * @param keyPair keypair to verify the signature with
   * @throws BadRequestException if the signature is invalid
   * @throws InternalServerErrorException if the preimage cannot be hashed
   */
  private void verifyServerSignature(SorobanAuthorizationEntry entry, KeyPair keyPair)
      throws BadRequestException, InternalServerErrorException {
    SCVal[] signatures = entry.getCredentials().getAddress().getSignature().getVec().getSCVec();
    if (signatures.length != 1) {
      throw new BadRequestException("Invalid number of signatures");
    }
    HashIDPreimage preimage =
        HashIDPreimage.builder()
            .discriminant(EnvelopeType.ENVELOPE_TYPE_SOROBAN_AUTHORIZATION)
            .sorobanAuthorization(
                HashIDPreimage.HashIDPreimageSorobanAuthorization.builder()
                    .networkID(
                        new Hash(
                            new Network(appConfig.getStellarNetworkPassphrase()).getNetworkId()))
                    .nonce(entry.getCredentials().getAddress().getNonce())
                    .invocation(entry.getRootInvocation())
                    .signatureExpirationLedger(
                        entry.getCredentials().getAddress().getSignatureExpirationLedger())
                    .build())
            .build();
    byte[] payload;
    try {
      payload = Util.hash(preimage.toXdrByteArray());
      // Extract and verify the signature
      if (!keyPair.verify(
          payload, signatures[0].getMap().getSCMap()[1].getVal().getBytes().getSCBytes())) {
        throw new BadRequestException("Invalid server signature");
      }
    } catch (IOException e) {
      throw new InternalServerErrorException("Failed to hash preimage");
    }
  }

  /**
   * Checks if an authorization entry has account credentials.
   *
   * @param entry entry to check
   * @return true if the entry has account credentials, false otherwise
   */
  private boolean hasAccountCredentials(SorobanAuthorizationEntry entry) {
    return entry
        .getCredentials()
        .getAddress()
        .getAddress()
        .getDiscriminant()
        .equals(SCAddressType.SC_ADDRESS_TYPE_ACCOUNT);
  }

  /**
   * Checks if an authorization entry matches a keypair.
   *
   * @param entry entry to check
   * @param keyPair keypair to check
   * @return true if the entry matches the keypair, false otherwise
   */
  private boolean matchesKeypairAccount(SorobanAuthorizationEntry entry, KeyPair keyPair) {
    SCAddress scAddress = entry.getCredentials().getAddress().getAddress();
    AccountID accountID = scAddress.getAccountId();
    KeyPair accountKeyPair = KeyPair.fromXdrPublicKey(accountID.getAccountID());

    return accountKeyPair.getAccountId().equals(keyPair.getAccountId());
  }

  /**
   * Extracts the arguments from a list of map entries.
   *
   * @param entries list of map entries
   * @return map of arguments
   */
  private Map<String, String> extractArgs(SCMapEntry[] entries) {
    return Arrays.stream(entries)
        .map(
            entry ->
                Map.entry(
                    entry.getKey().getSym().getSCSymbol().toString(),
                    entry.getVal().getStr().getSCString().toString()))
        .collect(HashMap::new, (m, v) -> m.put(v.getKey(), v.getValue()), HashMap::putAll);
  }

  /**
   * Verifies that the arguments have the expected values.
   *
   * @param entries list of map entries
   * @throws BadRequestException if the arguments are invalid
   * @throws SepException if the client domain is invalid
   */
  private void verifyArguments(SCMapEntry[] entries) throws BadRequestException, SepException {
    Map<String, String> argsMap = extractArgs(entries);

    if (!sep45Config.getHomeDomains().contains(argsMap.get(KEY_HOME_DOMAIN))) {
      Log.debugF(
          "Invalid home domain: {} allowed: {}",
          argsMap.get(KEY_HOME_DOMAIN),
          sep45Config.getHomeDomains());
      throw new BadRequestException("Invalid home domain");
    }

    KeyPair keyPair = KeyPair.fromSecretSeed(secretConfig.getSep10SigningSeed());
    if (!keyPair.getAccountId().equals(argsMap.get(KEY_HOME_DOMAIN_ADDRESS))) {
      throw new BadRequestException("Invalid home domain address");
    }

    if (!sep45Config.getWebAuthDomain().equals(argsMap.get(KEY_WEB_AUTH_DOMAIN))) {
      throw new BadRequestException("Invalid web auth domain");
    }

    if (argsMap.containsKey(KEY_CLIENT_DOMAIN)) {
      String clientDomainSigner =
          ClientDomainHelper.fetchSigningKeyFromClientDomain(argsMap.get(KEY_CLIENT_DOMAIN), false);
      if (!clientDomainSigner.equals(argsMap.get(KEY_CLIENT_DOMAIN_ADDRESS))) {
        throw new BadRequestException("Invalid client domain address");
      }
    }
  }
}
