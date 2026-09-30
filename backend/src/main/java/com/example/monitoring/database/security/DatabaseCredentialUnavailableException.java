package com.example.monitoring.database.security;

/** A single target's encrypted credentials cannot be used for collection. */
public class DatabaseCredentialUnavailableException extends RuntimeException {

    private final long databaseConfigId;
    private final long configVersion;
    private final Integer usernameKeyVersion;
    private final Integer passwordKeyVersion;

    public DatabaseCredentialUnavailableException(long databaseConfigId, long configVersion,
                                                  Integer usernameKeyVersion, Integer passwordKeyVersion,
                                                  Throwable cause) {
        super("Encrypted credentials unavailable for databaseConfigId=" + databaseConfigId, cause);
        this.databaseConfigId = databaseConfigId;
        this.configVersion = configVersion;
        this.usernameKeyVersion = usernameKeyVersion;
        this.passwordKeyVersion = passwordKeyVersion;
    }

    public long databaseConfigId() {
        return databaseConfigId;
    }

    public long configVersion() {
        return configVersion;
    }

    public Integer usernameKeyVersion() {
        return usernameKeyVersion;
    }

    public Integer passwordKeyVersion() {
        return passwordKeyVersion;
    }
}
