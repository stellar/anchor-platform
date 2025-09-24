package org.stellar.anchor;

public interface MoreInfoUrlConstructor {
  String construct(SepTransaction txn, String lang);
}
