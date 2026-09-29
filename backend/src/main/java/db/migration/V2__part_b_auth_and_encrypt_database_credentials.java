package db.migration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/** Part B-owned V2. A owns V1 and must register it before this migration is enabled. */
public class V2__part_b_auth_and_encrypt_database_credentials extends BaseJavaMigration {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        KeyMaterial key = loadActiveKey();

        migrateLegacyAccessLogs(connection);
        createAuthTables(connection);
        addDatabaseSecurityColumns(connection);
        migrateLegacyCredentials(connection, key);
        preserveLegacyBlockedState(connection);
    }

    private void migrateLegacyAccessLogs(Connection connection) throws SQLException {
        if (!tableExists(connection, "audit_logs") || !columnExists(connection, "audit_logs", "http_method")) return;
        if (tableExists(connection, "access_logs")) {
            throw new FlywayException("Both legacy audit_logs and access_logs exist; refusing ambiguous audit migration.");
        }
        execute(connection, "ALTER TABLE audit_logs RENAME TO access_logs");
        execute(connection, "ALTER TABLE access_logs RENAME COLUMN http_method TO method");
        execute(connection, "ALTER TABLE access_logs RENAME COLUMN request_uri TO path");
        execute(connection, "ALTER TABLE access_logs RENAME COLUMN http_status TO status_code");
        execute(connection, "ALTER TABLE access_logs RENAME COLUMN execution_time_ms TO duration_ms");
        execute(connection, "ALTER TABLE access_logs RENAME COLUMN timestamp TO occurred_at");
        String legacyTimeZone;
        try {
            legacyTimeZone = ZoneId.of(required("LEGACY_TIME_ZONE")).getId().replace("'", "''");
        } catch (DateTimeException exception) {
            throw new FlywayException("LEGACY_TIME_ZONE is not a valid time zone.", exception);
        }
        execute(connection, "ALTER TABLE access_logs ALTER COLUMN occurred_at TYPE TIMESTAMPTZ "
                + "USING occurred_at AT TIME ZONE '" + legacyTimeZone + "'");
        execute(connection, "ALTER TABLE access_logs ADD COLUMN actor_id BIGINT NULL");
        execute(connection, "ALTER TABLE access_logs ADD COLUMN request_id UUID NULL");
        execute(connection, "UPDATE access_logs SET method=COALESCE(method,'UNKNOWN'), path=COALESCE(path,'/'), "
                + "status_code=COALESCE(status_code,0), duration_ms=COALESCE(duration_ms,0)");
        try (PreparedStatement select = connection.prepareStatement("SELECT id FROM access_logs WHERE request_id IS NULL");
             ResultSet rows = select.executeQuery();
             PreparedStatement update = connection.prepareStatement("UPDATE access_logs SET request_id=? WHERE id=?")) {
            while (rows.next()) {
                update.setObject(1, UUID.randomUUID());
                update.setLong(2, rows.getLong(1));
                update.addBatch();
            }
            update.executeBatch();
        }
        execute(connection, "ALTER TABLE access_logs ALTER COLUMN method SET NOT NULL");
        execute(connection, "ALTER TABLE access_logs ALTER COLUMN path SET NOT NULL");
        execute(connection, "ALTER TABLE access_logs ALTER COLUMN status_code SET NOT NULL");
        execute(connection, "ALTER TABLE access_logs ALTER COLUMN duration_ms SET NOT NULL");
        execute(connection, "ALTER TABLE access_logs ALTER COLUMN request_id SET NOT NULL");
    }

    private void createAuthTables(Connection connection) throws SQLException {
        execute(connection, """
                CREATE TABLE IF NOT EXISTS users (
                    id BIGSERIAL PRIMARY KEY,
                    email VARCHAR(254) NOT NULL UNIQUE,
                    display_name VARCHAR(100) NOT NULL,
                    password_hash VARCHAR(512) NOT NULL,
                    role VARCHAR(16) NOT NULL,
                    enabled BOOLEAN NOT NULL,
                    auth_version BIGINT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL
                )
                """);
        execute(connection, """
                CREATE TABLE IF NOT EXISTS auth_sessions (
                    sid UUID PRIMARY KEY,
                    user_id BIGINT NOT NULL REFERENCES users(id),
                    current_refresh_hash VARCHAR(64) NOT NULL UNIQUE,
                    created_at TIMESTAMPTZ NOT NULL,
                    expires_at TIMESTAMPTZ NOT NULL,
                    revoked_at TIMESTAMPTZ NULL,
                    auth_version BIGINT NOT NULL
                )
                """);
        execute(connection, """
                CREATE TABLE IF NOT EXISTS used_refresh_tokens (
                    token_hash VARCHAR(64) PRIMARY KEY,
                    sid UUID NOT NULL REFERENCES auth_sessions(sid),
                    used_at TIMESTAMPTZ NOT NULL,
                    expires_at TIMESTAMPTZ NOT NULL
                )
                """);
        execute(connection, "CREATE INDEX IF NOT EXISTS idx_auth_sessions_user_id ON auth_sessions(user_id)");
        execute(connection, "CREATE INDEX IF NOT EXISTS idx_auth_sessions_expires_at ON auth_sessions(expires_at)");
        execute(connection, """
                CREATE TABLE IF NOT EXISTS audit_logs (
                    id BIGSERIAL PRIMARY KEY, actor_id BIGINT NULL, action VARCHAR(32) NOT NULL,
                    target_type VARCHAR(32) NOT NULL, target_id VARCHAR(255), database_config_id BIGINT NULL,
                    result VARCHAR(16) NOT NULL, occurred_at TIMESTAMPTZ NOT NULL, client_ip VARCHAR(45) NOT NULL,
                    request_id UUID NOT NULL, summary VARCHAR(500) NOT NULL
                )
                """);
        execute(connection, "CREATE INDEX IF NOT EXISTS idx_audit_logs_occurred_id ON audit_logs (occurred_at DESC, id DESC)");
        execute(connection, """
                CREATE TABLE IF NOT EXISTS access_logs (
                    id BIGSERIAL PRIMARY KEY, actor_id BIGINT NULL, method VARCHAR(16) NOT NULL,
                    path VARCHAR(500) NOT NULL, status_code INTEGER NOT NULL, duration_ms BIGINT NOT NULL,
                    client_ip VARCHAR(45) NOT NULL, occurred_at TIMESTAMPTZ NOT NULL, request_id UUID NOT NULL
                )
                """);
        execute(connection, "CREATE INDEX IF NOT EXISTS idx_access_logs_occurred_id ON access_logs (occurred_at DESC, id DESC)");
    }

    private void addDatabaseSecurityColumns(Connection connection) throws SQLException {
        execute(connection, "ALTER TABLE database_configs ADD COLUMN IF NOT EXISTS username_key_version INTEGER");
        execute(connection, "ALTER TABLE database_configs ADD COLUMN IF NOT EXISTS username_nonce BYTEA");
        execute(connection, "ALTER TABLE database_configs ADD COLUMN IF NOT EXISTS username_ciphertext BYTEA");
        execute(connection, "ALTER TABLE database_configs ADD COLUMN IF NOT EXISTS password_key_version INTEGER");
        execute(connection, "ALTER TABLE database_configs ADD COLUMN IF NOT EXISTS password_nonce BYTEA");
        execute(connection, "ALTER TABLE database_configs ADD COLUMN IF NOT EXISTS password_ciphertext BYTEA");
        execute(connection, "ALTER TABLE database_configs ADD COLUMN IF NOT EXISTS config_version BIGINT NOT NULL DEFAULT 1");
        execute(connection, "ALTER TABLE database_configs ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP NULL");
        execute(connection, "ALTER TABLE database_configs ADD COLUMN IF NOT EXISTS last_success_at TIMESTAMP NULL");
    }

    private void migrateLegacyCredentials(Connection connection, KeyMaterial key) throws Exception {
        boolean hasUsername = columnExists(connection, "database_configs", "username");
        boolean hasPassword = columnExists(connection, "database_configs", "password");
        if (hasUsername != hasPassword) {
            throw new FlywayException("database_configs has only one legacy credential column; refusing partial migration.");
        }

        if (hasUsername) {
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT id, username, password,
                           username_key_version, username_nonce, username_ciphertext,
                           password_key_version, password_nonce, password_ciphertext
                    FROM database_configs ORDER BY id FOR UPDATE
                    """); ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    migrateRow(connection, rows, key);
                }
            }
        }

        verifyEveryRowEncrypted(connection);
        if (hasUsername) {
            execute(connection, "ALTER TABLE database_configs DROP COLUMN username");
            execute(connection, "ALTER TABLE database_configs DROP COLUMN password");
        }
    }

    private void migrateRow(Connection connection, ResultSet row, KeyMaterial key) throws Exception {
        long id = row.getLong("id");
        String username = row.getString("username");
        String password = row.getString("password");
        boolean encryptedComplete = encryptedComplete(row);
        boolean encryptedAny = encryptedAny(row);

        if (encryptedAny && !encryptedComplete) {
            throw new FlywayException("Partial encrypted credential state for database_config id=" + id);
        }

        if (username == null && password == null) {
            if (!encryptedComplete) {
                throw new FlywayException("Missing credentials for database_config id=" + id);
            }
            verifyDecryptable(id, row);
            return;
        }
        if (username == null || password == null || encryptedAny) {
            throw new FlywayException("Mixed plaintext/encrypted credential state for database_config id=" + id);
        }

        CipherValue encryptedUsername = encrypt(key, id, "username", username);
        CipherValue encryptedPassword = encrypt(key, id, "password", password);
        if (!username.equals(decrypt(key.secretKey(), id, "username", encryptedUsername.nonce(), encryptedUsername.ciphertext()))
                || !password.equals(decrypt(key.secretKey(), id, "password", encryptedPassword.nonce(), encryptedPassword.ciphertext()))) {
            throw new FlywayException("Credential encryption round-trip failed for database_config id=" + id);
        }

        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE database_configs
                SET username_key_version=?, username_nonce=?, username_ciphertext=?,
                    password_key_version=?, password_nonce=?, password_ciphertext=?
                WHERE id=?
                """)) {
            update.setInt(1, key.version());
            update.setBytes(2, encryptedUsername.nonce());
            update.setBytes(3, encryptedUsername.ciphertext());
            update.setInt(4, key.version());
            update.setBytes(5, encryptedPassword.nonce());
            update.setBytes(6, encryptedPassword.ciphertext());
            update.setLong(7, id);
            if (update.executeUpdate() != 1) {
                throw new FlywayException("Credential update count mismatch for database_config id=" + id);
            }
        }
    }

    private boolean encryptedComplete(ResultSet row) throws SQLException {
        return row.getObject("username_key_version") != null && row.getBytes("username_nonce") != null
                && row.getBytes("username_ciphertext") != null && row.getObject("password_key_version") != null
                && row.getBytes("password_nonce") != null && row.getBytes("password_ciphertext") != null;
    }

    private boolean encryptedAny(ResultSet row) throws SQLException {
        return row.getObject("username_key_version") != null || row.getBytes("username_nonce") != null
                || row.getBytes("username_ciphertext") != null || row.getObject("password_key_version") != null
                || row.getBytes("password_nonce") != null || row.getBytes("password_ciphertext") != null;
    }

    private void verifyDecryptable(long id, ResultSet row) throws Exception {
        SecretKeySpec usernameKey = loadKeyVersion(row.getInt("username_key_version"));
        SecretKeySpec passwordKey = loadKeyVersion(row.getInt("password_key_version"));
        decrypt(usernameKey, id, "username", row.getBytes("username_nonce"), row.getBytes("username_ciphertext"));
        decrypt(passwordKey, id, "password", row.getBytes("password_nonce"), row.getBytes("password_ciphertext"));
    }

    private void verifyEveryRowEncrypted(Connection connection) throws SQLException {
        String sql = """
                SELECT COUNT(*) FROM database_configs
                WHERE username_key_version IS NULL OR username_nonce IS NULL OR username_ciphertext IS NULL
                   OR password_key_version IS NULL OR password_nonce IS NULL OR password_ciphertext IS NULL
                """;
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            if (result.getLong(1) != 0) {
                throw new FlywayException("Not every database_config row has complete encrypted credentials.");
            }
        }
    }

    private void preserveLegacyBlockedState(Connection connection) throws SQLException {
        if (columnExists(connection, "database_configs", "status")) {
            execute(connection, "UPDATE database_configs SET enabled=FALSE WHERE status='BLOCKED'");
        }
    }

    private KeyMaterial loadActiveKey() {
        String active = required("DB_CONFIG_ACTIVE_KEY_VERSION");
        int version;
        try {
            version = Integer.parseInt(active);
        } catch (NumberFormatException exception) {
            throw new FlywayException("DB_CONFIG_ACTIVE_KEY_VERSION must be an integer.", exception);
        }
        return new KeyMaterial(version, loadKeyVersion(version));
    }

    private SecretKeySpec loadKeyVersion(int version) {
        try {
            Map<String, String> keys = new ObjectMapper().readValue(
                    required("DB_CONFIG_ENCRYPTION_KEYS"), new TypeReference<Map<String, String>>() { });
            String encoded = keys.get(Integer.toString(version));
            if (encoded == null) throw new FlywayException("Missing database encryption key version=" + version);
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length != 32) throw new FlywayException("Database encryption key must be exactly 32 bytes.");
            return new SecretKeySpec(decoded, "AES");
        } catch (FlywayException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new FlywayException("Invalid DB_CONFIG_ENCRYPTION_KEYS JSON/base64.", exception);
        }
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new FlywayException(name + " is required for V2 migration.");
        return value;
    }

    private CipherValue encrypt(KeyMaterial key, long id, String field, String plaintext) throws Exception {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key.secretKey(), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad(id, field));
        return new CipherValue(nonce, cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
    }

    private String decrypt(SecretKeySpec key, long id, String field, byte[] nonce, byte[] ciphertext) throws Exception {
        if (nonce == null || nonce.length != NONCE_BYTES || ciphertext == null || ciphertext.length < 16) {
            throw new FlywayException("Invalid encrypted credential format for database_config id=" + id);
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad(id, field));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private byte[] aad(long id, String field) {
        return ("database:" + id + ":" + field).getBytes(StandardCharsets.UTF_8);
    }

    private boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(null, null, table, column)) {
            return columns.next();
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(null, null, table, new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record KeyMaterial(int version, SecretKeySpec secretKey) { }
    private record CipherValue(byte[] nonce, byte[] ciphertext) { }
}
