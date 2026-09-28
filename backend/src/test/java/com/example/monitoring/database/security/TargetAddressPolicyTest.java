package com.example.monitoring.database.security;

import com.example.monitoring.common.api.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class TargetAddressPolicyTest {
    @Test
    void allowsEveryResolvedAddressOnlyInsideConfiguredCidrAndPort() {
        TargetAddressPolicy policy = new TargetAddressPolicy("127.0.0.0/8", "3306,13306");
        assertThat(policy.resolveAndValidate("127.0.0.1", 3306)).isNotEmpty();
    }

    @Test
    void rejectsAddressOutsideCidr() {
        TargetAddressPolicy policy = new TargetAddressPolicy("10.0.0.0/8", "3306");
        assertThatThrownBy(() -> policy.resolveAndValidate("127.0.0.1", 3306))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsWhenAllowedCidrsAreMissing() {
        TargetAddressPolicy policy = new TargetAddressPolicy("", "3306");
        assertThatThrownBy(() -> policy.resolveAndValidate("127.0.0.1", 3306))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsPortOutsideAllowList() {
        TargetAddressPolicy policy = new TargetAddressPolicy("127.0.0.0/8", "3306");
        assertThatThrownBy(() -> policy.resolveAndValidate("127.0.0.1", 3307))
                .isInstanceOf(ApiException.class);
    }
}
