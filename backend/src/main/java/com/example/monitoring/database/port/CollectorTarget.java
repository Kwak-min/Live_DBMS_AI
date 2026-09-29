package com.example.monitoring.database.port;

/** Decrypted credentials exist only in this short-lived internal value. */
public final class CollectorTarget {
    private final long id;
    private final long configVersion;
    private final String name;
    private final String host;
    private final int port;
    private final String databaseName;
    private final String username;
    private final String password;
    private final boolean enabled;

    public CollectorTarget(long id, long configVersion, String name, String host, int port,
                           String databaseName, String username, String password, boolean enabled) {
        this.id = id;
        this.configVersion = configVersion;
        this.name = name;
        this.host = host;
        this.port = port;
        this.databaseName = databaseName;
        this.username = username;
        this.password = password;
        this.enabled = enabled;
    }

    public long id() { return id; }
    public long configVersion() { return configVersion; }
    public String name() { return name; }
    public String host() { return host; }
    public int port() { return port; }
    public String databaseName() { return databaseName; }
    public String username() { return username; }
    public String password() { return password; }
    public boolean enabled() { return enabled; }

    @Override
    public String toString() {
        return "CollectorTarget[id=" + id + ", configVersion=" + configVersion + ", name=" + name
                + ", host=" + host + ", port=" + port + ", databaseName=" + databaseName
                + ", enabled=" + enabled + ", credentials=REDACTED]";
    }
}
