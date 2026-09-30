package com.example.monitoring.database.service;

import com.example.monitoring.database.security.DatabaseCredentialCrypto;
import com.example.monitoring.database.security.DatabaseCredentialUnavailableException;
import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.repository.DatabaseConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JpaTargetProviderTest {

    private final DatabaseConfigRepository repository = mock(DatabaseConfigRepository.class);
    private final DatabaseCredentialCrypto crypto = mock(DatabaseCredentialCrypto.class);
    private final JpaTargetProvider provider = new JpaTargetProvider(repository, crypto);

    @Test
    void listingDoesNotDecryptAndOneBrokenTargetDoesNotHideTheOthers() {
        DatabaseConfig broken = config(1L);
        DatabaseConfig healthy = config(2L);
        healthy.storeEncryptedUsername(1, new byte[12], new byte[16]);
        healthy.storeEncryptedPassword(1, new byte[12], new byte[16]);
        when(repository.findByEnabledTrueAndDeletedAtIsNull()).thenReturn(List.of(broken, healthy));
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(broken));
        when(repository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(healthy));
        when(crypto.decrypt(eq(2L), eq("username"), eq(1), any(), any())).thenReturn("user");
        when(crypto.decrypt(eq(2L), eq("password"), eq(1), any(), any())).thenReturn("secret");

        assertThat(provider.listEnabled()).extracting("id").containsExactly(1L, 2L);
        assertThatThrownBy(() -> provider.getForCollection(1L))
                .isInstanceOf(DatabaseCredentialUnavailableException.class)
                .hasMessageContaining("databaseConfigId=1");
        assertThat(provider.getForCollection(2L)).get().extracting("username").isEqualTo("user");
    }

    private static DatabaseConfig config(long id) {
        return DatabaseConfig.builder().id(id).configVersion(1L).name("db" + id).host("localhost")
                .port(3306).enabled(true).build();
    }
}
