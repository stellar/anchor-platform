package org.stellar.anchor.platform.service;

public class DepositInfoSelfGeneratorBase {
    static long lastGeneratedMemoId = System.currentTimeMillis();

    protected String generateMemoId() {
        return String.valueOf(lastGeneratedMemoId++);
    }
}
