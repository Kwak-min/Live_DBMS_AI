package com.example.monitoring.database.security;

import com.example.monitoring.database.port.CollectorTarget;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

@Component
public class TargetConnectionFactory {
    private final TargetAddressPolicy addressPolicy;
    private final int timeoutSeconds;
    private final boolean localProfile;

    public TargetConnectionFactory(TargetAddressPolicy addressPolicy,
            @Value("${app.collector.connection-timeout-seconds:5}") int timeoutSeconds,
            Environment environment) {
        this.addressPolicy = addressPolicy;
        this.timeoutSeconds = timeoutSeconds;
        this.localProfile = environment.acceptsProfiles(Profiles.of("local"));
    }

    public Connection open(CollectorTarget target) throws SQLException {
        List<InetAddress> addresses = addressPolicy.resolveAndValidate(target.host(), target.port());
        InetAddress pinned = addresses.get(0);
        String address = pinned.getHostAddress();
        if (address.contains(":")) address = '[' + address + ']';
        String databaseName = target.databaseName() == null ? "" : target.databaseName();
        String tlsOptions = localProfile ? "&sslMode=disable"
                : "&sslMode=verify-full&tlsSocketType=" + PinnedHostTlsSocketPlugin.TYPE;
        String jdbcUrl = String.format("jdbc:mariadb://%s:%d/%s?connectTimeout=%d&socketTimeout=%d%s",
                address, target.port(), databaseName, timeoutSeconds * 1000, timeoutSeconds * 1000, tlsOptions);
        try (PinnedHostTlsSocketPlugin.Scope ignored = PinnedHostTlsSocketPlugin.expect(target.host())) {
            return DriverManager.getConnection(jdbcUrl, target.username(), target.password());
        }
    }
}
