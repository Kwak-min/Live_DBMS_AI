package com.example.monitoring.database.security;

import org.mariadb.jdbc.Configuration;
import org.mariadb.jdbc.export.ExceptionFactory;
import org.mariadb.jdbc.plugin.TlsSocketPlugin;
import org.mariadb.jdbc.plugin.tls.main.DefaultTlsSocketPlugin;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.Socket;
import java.sql.SQLException;

/** Connects to a validated IP while verifying the certificate against the original configured host. */
public final class PinnedHostTlsSocketPlugin implements TlsSocketPlugin {
    public static final String TYPE = "PINNED_HOST";
    private static final ThreadLocal<String> EXPECTED_HOST = new ThreadLocal<>();
    private final DefaultTlsSocketPlugin delegate = new DefaultTlsSocketPlugin();

    public static Scope expect(String hostname) {
        EXPECTED_HOST.set(hostname);
        return EXPECTED_HOST::remove;
    }

    @Override public String type() { return TYPE; }
    @Override public SSLSocketFactory getSocketFactory(Configuration configuration, ExceptionFactory exceptionFactory)
            throws SQLException { return delegate.getSocketFactory(configuration, exceptionFactory); }
    @Override public SSLSocket createSocket(Socket socket, SSLSocketFactory factory) throws IOException {
        return delegate.createSocket(socket, factory);
    }
    @Override public void verify(String connectedHost, SSLSession session, long serverThreadId) throws SSLException {
        String expected = EXPECTED_HOST.get();
        if (expected == null || expected.isBlank()) throw new SSLException("Expected TLS hostname is unavailable");
        delegate.verify(expected, session, serverThreadId);
    }

    @FunctionalInterface public interface Scope extends AutoCloseable { @Override void close(); }
}
