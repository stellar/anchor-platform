package org.stellar.anchor.xdr;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.stellar.sdk.Base64Factory;
import org.stellar.sdk.xdr.SorobanAuthorizationEntry;
import org.stellar.sdk.xdr.XdrDataInputStream;
import org.stellar.sdk.xdr.XdrDataOutputStream;

public class SorobanAuthorizationEntryList {
  private SorobanAuthorizationEntry[] authorizationEntryList;

  public SorobanAuthorizationEntryList() {}

  public SorobanAuthorizationEntryList(SorobanAuthorizationEntry[] authorizationEntryList) {
    this.authorizationEntryList = authorizationEntryList;
  }

  public SorobanAuthorizationEntry[] getAuthorizationEntryList() {
    return authorizationEntryList;
  }

  public void encode(XdrDataOutputStream stream) throws IOException {
    int size = getAuthorizationEntryList().length;
    stream.writeInt(size);
    for (int i = 0; i < size; i++) {
      authorizationEntryList[i].encode(stream);
    }
  }

  public static SorobanAuthorizationEntryList decode(XdrDataInputStream stream) throws IOException {
    int size = stream.readInt();
    SorobanAuthorizationEntryList instance = new SorobanAuthorizationEntryList();
    instance.authorizationEntryList = new SorobanAuthorizationEntry[size];
    for (int i = 0; i < size; i++) {
      instance.authorizationEntryList[i] = SorobanAuthorizationEntry.decode(stream);
    }
    return instance;
  }

  public static SorobanAuthorizationEntryList fromXdrBase64(String xdr) throws IOException {
    byte[] bytes = Base64Factory.getInstance().decode(xdr);
    return fromXdrByteArray(bytes);
  }

  public static SorobanAuthorizationEntryList fromXdrByteArray(byte[] bytes) throws IOException {
    ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);
    XdrDataInputStream xdrDataInputStream = new XdrDataInputStream(byteArrayInputStream);
    return decode(xdrDataInputStream);
  }

  public String toXdrBase64() throws IOException {
    return Base64Factory.getInstance().encodeToString(toXdrByteArray());
  }

  public byte[] toXdrByteArray() throws IOException {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    XdrDataOutputStream xdrDataOutputStream = new XdrDataOutputStream(byteArrayOutputStream);
    encode(xdrDataOutputStream);
    return byteArrayOutputStream.toByteArray();
  }
}
