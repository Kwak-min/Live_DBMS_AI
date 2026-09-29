package com.example.monitoring.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

/** Uses X-Forwarded-For only when the direct peer is a configured trusted proxy. */
@Component
public class ClientIpResolver {
    private final List<Cidr> trustedProxies;

    public ClientIpResolver(@Value("${app.security.trusted-proxy-cidrs:}") String configuredCidrs) {
        trustedProxies = Arrays.stream(configuredCidrs.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).map(Cidr::parse).toList();
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!isTrusted(remote)) return remote;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return remote;
        String[] hops = forwarded.split(",");
        for (int index = hops.length - 1; index >= 0; index--) {
            String hop = hops[index].trim();
            if (isNumericAddress(hop) && !isTrusted(hop)) return hop;
        }
        return remote;
    }

    private boolean isTrusted(String address) {
        if (!isNumericAddress(address)) return false;
        return trustedProxies.stream().anyMatch(cidr -> cidr.contains(address));
    }

    private static boolean isNumericAddress(String value) {
        return value != null && value.matches("[0-9a-fA-F:.]+") && !value.isBlank();
    }

    private record Cidr(byte[] network, int prefixBits) {
        static Cidr parse(String value) {
            try {
                String[] parts = value.split("/", -1);
                if (parts.length != 2 || !isNumericAddress(parts[0])) throw new IllegalArgumentException();
                byte[] network = InetAddress.getByName(parts[0]).getAddress();
                int prefix = Integer.parseInt(parts[1]);
                if (prefix < 0 || prefix > network.length * 8) throw new IllegalArgumentException();
                return new Cidr(network, prefix);
            } catch (Exception exception) {
                throw new IllegalArgumentException("Invalid TRUSTED_PROXY_CIDRS entry: " + value, exception);
            }
        }

        boolean contains(String value) {
            try {
                byte[] candidate = InetAddress.getByName(value).getAddress();
                if (candidate.length != network.length) return false;
                int fullBytes = prefixBits / 8;
                int remaining = prefixBits % 8;
                for (int i = 0; i < fullBytes; i++) if (candidate[i] != network[i]) return false;
                if (remaining == 0) return true;
                int mask = 0xFF << (8 - remaining);
                return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
            } catch (Exception exception) {
                return false;
            }
        }
    }
}
