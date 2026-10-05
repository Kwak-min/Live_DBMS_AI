package com.example.monitoring.notification.delivery;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.Executor;

@Component
public final class PostgresDeliveryLease implements AutoCloseable {

    private static final int LOCK_NAMESPACE = 807_199;
    private static final int LOCK_KEY = 17;
    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    private final DataSource dataSource;
    private Connection connection;
    private int backendPid = -1;
    private long acquiredNanoTime;

    public PostgresDeliveryLease(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public synchronized boolean tryAcquire() {
        if (verifyHeld()) {
            return true;
        }
        Connection candidate = null;
        try {
            candidate = dataSource.getConnection();
            candidate.setAutoCommit(true);
            if (!tryLock(candidate)) {
                candidate.close();
                return false;
            }
            connection = candidate;
            backendPid = queryBackendPid(candidate);
            acquiredNanoTime = System.nanoTime();
            return true;
        } catch (SQLException exception) {
            abort(candidate);
            clear();
            return false;
        }
    }

    public synchronized boolean verifyHeld() {
        if (connection == null) {
            return false;
        }
        try {
            if (connection.isClosed() || !connection.isValid(1)) {
                discardConnection();
                return false;
            }
            if (queryBackendPid(connection) != backendPid) {
                discardConnection();
                return false;
            }
            return true;
        } catch (SQLException exception) {
            discardConnection();
            return false;
        }
    }

    public synchronized long acquiredNanoTime() {
        return acquiredNanoTime;
    }

    public synchronized int backendPid() {
        return backendPid;
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        Connection owned = connection;
        if (owned == null) {
            return;
        }
        try {
            if (!owned.isClosed() && owned.isValid(1)) {
                try (PreparedStatement statement = owned.prepareStatement(
                        "SELECT pg_advisory_unlock(?, ?)")) {
                    statement.setInt(1, LOCK_NAMESPACE);
                    statement.setInt(2, LOCK_KEY);
                    statement.executeQuery().close();
                }
                owned.close();
            } else {
                abort(owned);
            }
        } catch (SQLException exception) {
            abort(owned);
        } finally {
            clear();
        }
    }

    private boolean tryLock(Connection value) throws SQLException {
        try (PreparedStatement statement = value.prepareStatement(
                "SELECT pg_try_advisory_lock(?, ?)")) {
            statement.setInt(1, LOCK_NAMESPACE);
            statement.setInt(2, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private int queryBackendPid(Connection value) throws SQLException {
        try (Statement statement = value.createStatement();
             ResultSet result = statement.executeQuery("SELECT pg_backend_pid()")) {
            if (!result.next()) {
                throw new SQLException("PostgreSQL lease session did not return a backend identifier.");
            }
            return result.getInt(1);
        }
    }

    private void discardConnection() {
        abort(connection);
        clear();
    }

    private void abort(Connection value) {
        if (value == null) {
            return;
        }
        try {
            value.abort(DIRECT_EXECUTOR);
        } catch (SQLException | RuntimeException abortFailure) {
            // Closing the pool proxy below is still required after a failed physical abort.
        } finally {
            try {
                value.close();
            } catch (SQLException | RuntimeException closeFailure) {
                // The lease is already failed closed; there is no safe session to return or reuse.
            }
        }
    }

    private void clear() {
        connection = null;
        backendPid = -1;
        acquiredNanoTime = 0;
    }
}
