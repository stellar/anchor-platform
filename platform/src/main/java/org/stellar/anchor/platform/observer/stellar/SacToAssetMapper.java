package org.stellar.anchor.platform.observer.stellar;

import static org.stellar.anchor.util.Log.infoF;
import static org.stellar.sdk.xdr.ContractExecutableType.CONTRACT_EXECUTABLE_STELLAR_ASSET;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.stellar.sdk.Address;
import org.stellar.sdk.SorobanServer;
import org.stellar.sdk.responses.sorobanrpc.GetLedgerEntriesResponse;
import org.stellar.sdk.scval.Scv;
import org.stellar.sdk.xdr.*;

public class SacToAssetMapper {
  SorobanServer sorobanServer;
  HashMap<String, Asset> sacToAssetMap = new HashMap<>();

  public SacToAssetMapper(SorobanServer sorobanServer) {
    this.sorobanServer = sorobanServer;
  }

  public Asset test(String sac) {
    return null;
  }

  /**
   * Maps a Stellar Asset Contract ID to an Asset object.
   *
   * @param sac the Stellar Asset Contract ID
   * @return the corresponding Asset object
   */
  public Asset getAssetFromSac(String sac) {
    if (sacToAssetMap.containsKey(sac)) {
      return sacToAssetMap.get(sac);
    }

    SCContractInstance contractInstance;
    try {
      contractInstance = fetchContractInstance(sac);
    } catch (IOException e) {
      return null;
    }
    if (contractInstance == null) {
      return null;
    }
    if (contractInstance.getExecutable().getDiscriminant() != CONTRACT_EXECUTABLE_STELLAR_ASSET) {
      infoF("Contract {} is not a Stellar Asset Contract, its transfer events are ignored", sac);
      sacToAssetMap.put(sac, null);
      return null;
    }

    SCVal metadata =
        Scv.fromMap(
                SCVal.builder()
                    .discriminant(SCValType.SCV_MAP)
                    .map(contractInstance.getStorage())
                    .build())
            .get(Scv.toSymbol("METADATA"));
    if (metadata == null) {
      return null;
    }

    Map<SCVal, SCVal> scMap = Scv.fromMap(metadata);
    SCVal scAssetName = scMap.get(Scv.toSymbol("name"));
    if (scAssetName == null) {
      return null;
    }

    String assetName = scAssetName.getStr().getSCString().toString();
    Asset asset = org.stellar.sdk.Asset.create(assetName).toXdr();
    sacToAssetMap.put(sac, asset);
    return asset;
  }

  public boolean isKnownNonSac(String sac) {
    return sacToAssetMap.containsKey(sac) && sacToAssetMap.get(sac) == null;
  }

  private SCContractInstance fetchContractInstance(String sac) throws IOException {
    List<LedgerKey> ledgerKeys =
        Collections.singletonList(
            LedgerKey.builder()
                .discriminant(LedgerEntryType.CONTRACT_DATA)
                .contractData(
                    LedgerKey.LedgerKeyContractData.builder()
                        .contract(new Address(sac).toSCAddress())
                        .key(Scv.toLedgerKeyContractInstance())
                        .durability(ContractDataDurability.PERSISTENT)
                        .build())
                .build());

    GetLedgerEntriesResponse response = sorobanServer.getLedgerEntries(ledgerKeys);
    if (response == null || response.getEntries() == null || response.getEntries().isEmpty()) {
      return null;
    }
    return LedgerEntry.LedgerEntryData.fromXdrBase64(response.getEntries().get(0).getXdr())
        .getContractData()
        .getVal()
        .getInstance();
  }
}
