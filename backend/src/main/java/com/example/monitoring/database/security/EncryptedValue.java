package com.example.monitoring.database.security;

public record EncryptedValue(int keyVersion, byte[] nonce, byte[] ciphertext) {
    public EncryptedValue {
        nonce = nonce.clone();
        ciphertext = ciphertext.clone();
    }

    @Override public byte[] nonce() { return nonce.clone(); }
    @Override public byte[] ciphertext() { return ciphertext.clone(); }

    @Override
    public String toString() {
        return "EncryptedValue[keyVersion=" + keyVersion + ", redacted]";
    }
}
