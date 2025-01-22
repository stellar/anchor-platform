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
import org.stellar.anchor.xdr.SorobanAuthorizationEntryList;
import org.stellar.sdk.*;
import org.stellar.sdk.Transaction;
import org.stellar.sdk.operations.InvokeHostFunctionOperation;
import org.stellar.sdk.responses.sorobanrpc.SimulateTransactionResponse;
import org.stellar.sdk.scval.Scv;
import org.stellar.sdk.xdr.*;

@AllArgsConstructor
public class Sep45Service implements ISep45Service {
  private final AppConfig appConfig;
  private final SecretConfig secretConfig;
  private final Sep45Config sep45Config;
  private final Rpc rpc;
  private final JwtService jwtService;

  private static final String WEB_AUTH_VERIFY_FN = "web_auth_verify";
  private static final String WEB_AUTH_VERIFY_ACCOUNT_KEY = "account";
  private static final String WEB_AUTH_VERIFY_HOME_DOMAIN_KEY = "home_domain";
  private static final String WEB_AUTH_VERIFY_HOME_DOMAIN_ADDRESS_KEY = "home_domain_address";
  private static final String WEB_AUTH_VERIFY_CLIENT_DOMAIN_KEY = "client_domain";
  private static final String WEB_AUTH_VERIFY_CLIENT_DOMAIN_ADDRESS_KEY = "client_domain_address";
  private static final String WEB_AUTH_VERIFY_WEB_AUTH_DOMAIN_KEY = "web_auth_domain";

  @Override
  public ChallengeResponse getChallenge(ChallengeRequest request) throws AnchorException {
    KeyPair signingKeypair = KeyPair.fromSecretSeed(secretConfig.getSep10SigningSeed());
    KeyPair submittingKeypair =
        KeyPair.fromSecretSeed(secretConfig.getSep45SimulatingSigningSeed());
    TransactionBuilderAccount source =
        rpc.getAccount(submittingKeypair.getAccountId()); // this should be a different account
    LinkedHashMap<String, String> argsMap =
        new LinkedHashMap<>(
            Map.of(
                WEB_AUTH_VERIFY_ACCOUNT_KEY,
                request.getAccount(),
                WEB_AUTH_VERIFY_HOME_DOMAIN_KEY,
                request.getHomeDomain(),
                WEB_AUTH_VERIFY_HOME_DOMAIN_ADDRESS_KEY,
                signingKeypair.getAccountId(),
                WEB_AUTH_VERIFY_WEB_AUTH_DOMAIN_KEY,
                sep45Config.getWebAuthDomain()));
    if (!isEmpty(request.getClientDomain())) {
      String clientDomainSigner =
          ClientDomainHelper.fetchSigningKeyFromClientDomain(request.getClientDomain(), false);
      argsMap.put(WEB_AUTH_VERIFY_CLIENT_DOMAIN_KEY, request.getClientDomain());
      argsMap.put(WEB_AUTH_VERIFY_CLIENT_DOMAIN_ADDRESS_KEY, clientDomainSigner);
    }

    SCVal[] args = createArguments(argsMap);
    InvokeHostFunctionOperation operation =
        InvokeHostFunctionOperation.invokeContractFunctionOperationBuilder(
                sep45Config.getWebAuthContractId(), WEB_AUTH_VERIFY_FN, Arrays.asList(args))
            .sourceAccount(source.getAccountId())
            .build();

    Transaction transaction =
        new TransactionBuilder(source, new Network(appConfig.getStellarNetworkPassphrase()))
            .setBaseFee(Transaction.MIN_BASE_FEE)
            .addOperation(operation)
            .setTimeout(300)
            .build();

    SimulateTransactionResponse simulateTransactionResponse = rpc.simulateTransaction(transaction);

    List<SorobanAuthorizationEntry> authEntries = new ArrayList<>();
    if (simulateTransactionResponse.getError() != null) {
      throw new InternalServerErrorException("Failed to simulate transaction");
    } else {
      for (String xdr : simulateTransactionResponse.getResults().get(0).getAuth()) {
        try {
          SorobanAuthorizationEntry entry = SorobanAuthorizationEntry.fromXdrBase64(xdr);
          if (entry
              .getCredentials()
              .getAddress()
              .getAddress()
              .getDiscriminant()
              .equals(SCAddressType.SC_ADDRESS_TYPE_ACCOUNT)) {
            long sequenceNumber = rpc.getLatestLedger().getSequence().longValue();
            // Sign the entry with the anchor's key
            entry =
                authorizeEntry(
                    xdr,
                    signingKeypair,
                    sequenceNumber + 10,
                    new Network(appConfig.getStellarNetworkPassphrase()));
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
      KeyPair signingKeypair = KeyPair.fromSecretSeed(secretConfig.getSep10SigningSeed());
      SCAddress scAddress = entry.getCredentials().getAddress().getAddress();
      if (scAddress.getDiscriminant().equals(SCAddressType.SC_ADDRESS_TYPE_ACCOUNT)) {
        AccountID accountID = scAddress.getAccountId();
        KeyPair accountKeyPair = KeyPair.fromXdrPublicKey(accountID.getAccountID());
        if (accountKeyPair.getAccountId().equals(signingKeypair.getAccountId())) {
          verifyServerSignature(entry, signingKeypair);
        }
      }
    }

    KeyPair keyPair = KeyPair.fromSecretSeed(secretConfig.getSep45SimulatingSigningSeed());
    TransactionBuilderAccount source = rpc.getAccount(keyPair.getAccountId());

    InvokeHostFunctionOperation operation =
        InvokeHostFunctionOperation.invokeContractFunctionOperationBuilder(
                sep45Config.getWebAuthContractId(),
                WEB_AUTH_VERIFY_FN,
                Arrays.asList(firstEntryArgs))
            .sourceAccount(source.getAccountId())
            .auth(Arrays.asList(authEntries.getAuthorizationEntryList()))
            .build();

    Transaction transaction =
        new TransactionBuilder(source, new Network(appConfig.getStellarNetworkPassphrase()))
            .setBaseFee(Transaction.MIN_BASE_FEE)
            .addOperation(operation)
            .setTimeout(300)
            .build();

    SimulateTransactionResponse simulateTransactionResponse = rpc.simulateTransaction(transaction);
    if (simulateTransactionResponse.getError() != null) {
      throw new InvalidRequestException("Failed to simulate transaction");
    }

    Map<String, String> argsMap = extractArgs(firstEntryArgs[0].getMap().getSCMap());

    long issuedAt = Instant.now().getEpochSecond();
    String account = argsMap.get("account");
    String homeDomain = argsMap.get("home_domain");
    String clientDomain = argsMap.get("client_domain");

    String authUrl = "http://" + homeDomain + "/sep45/auth"; // TODO: check if this is right
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
      if (!keyPair.verify(
          payload, signatures[0].getMap().getSCMap()[1].getVal().getBytes().getSCBytes())) {
        throw new BadRequestException("Invalid server signature");
      }
    } catch (IOException e) {
      throw new InternalServerErrorException("Failed to hash preimage");
    }
  }

  private Map<String, String> extractArgs(SCMapEntry[] entries) {
    return Arrays.stream(entries)
        .map(
            entry ->
                Map.entry(
                    entry.getKey().getSym().getSCSymbol().toString(),
                    entry.getVal().getStr().getSCString().toString()))
        .collect(HashMap::new, (m, v) -> m.put(v.getKey(), v.getValue()), HashMap::putAll);
  }

  private void verifyArguments(SCMapEntry[] entries) throws BadRequestException, SepException {
    Map<String, String> argsMap = extractArgs(entries);

    if (!sep45Config.getHomeDomains().contains(argsMap.get(WEB_AUTH_VERIFY_HOME_DOMAIN_KEY))) {
      throw new BadRequestException("Invalid home domain");
    }

    KeyPair keyPair = KeyPair.fromSecretSeed(secretConfig.getSep10SigningSeed());
    if (!keyPair.getAccountId().equals(argsMap.get(WEB_AUTH_VERIFY_HOME_DOMAIN_ADDRESS_KEY))) {
      throw new BadRequestException("Invalid home domain address");
    }

    if (!sep45Config.getWebAuthDomain().equals(argsMap.get(WEB_AUTH_VERIFY_WEB_AUTH_DOMAIN_KEY))) {
      throw new BadRequestException("Invalid web auth domain");
    }

    if (argsMap.containsKey(WEB_AUTH_VERIFY_CLIENT_DOMAIN_KEY)) {
      String clientDomainSigner =
          ClientDomainHelper.fetchSigningKeyFromClientDomain(
              argsMap.get(WEB_AUTH_VERIFY_CLIENT_DOMAIN_KEY), false);
      if (!clientDomainSigner.equals(argsMap.get(WEB_AUTH_VERIFY_CLIENT_DOMAIN_ADDRESS_KEY))) {
        throw new BadRequestException("Invalid client domain address");
      }
    }
  }
}
