package com.example.monitoring.database.security;

import com.example.monitoring.common.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class TargetAddressPolicy {
    private final List<Cidr> allowedCidrs;
    private final Set<Integer> allowedPorts;

    public TargetAddressPolicy(@Value("${app.database-security.allowed-cidrs:}") String cidrs,
                               @Value("${app.database-security.allowed-ports:3306}") String ports) {
        allowedCidrs = Arrays.stream(cidrs.split(",")).map(String::trim).filter(value -> !value.isEmpty())
                .map(Cidr::parse).toList();
        try {
            allowedPorts = Arrays.stream(ports.split(",")).map(String::trim).filter(value -> !value.isEmpty())
                    .map(Integer::parseInt).peek(port -> { if (port < 1 || port > 65535) throw new IllegalArgumentException(); })
                    .collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("TARGET_DB_ALLOWED_PORTS contains an invalid port", exception);
        }
    }

    public List<InetAddress> resolveAndValidate(String host, int port) {
        if (!allowedPorts.contains(port)) throw rejected("대상 DB 포트가 허용 목록에 없습니다.");
        if (allowedCidrs.isEmpty()) throw rejected("TARGET_DB_ALLOWED_CIDRS가 설정되지 않았습니다.");
        try {
            List<InetAddress> addresses = List.of(InetAddress.getAllByName(host));
            if (addresses.isEmpty() || addresses.stream().anyMatch(address -> allowedCidrs.stream().noneMatch(cidr -> cidr.contains(address)))) {
                throw rejected("대상 DB 주소가 허용 CIDR 범위에 없습니다.");
            }
            return addresses;
        } catch (UnknownHostException exception) {
            throw rejected("대상 DB 호스트를 확인할 수 없습니다.");
        }
    }

    private ApiException rejected(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private record Cidr(byte[] network, int prefixBits) {
        static Cidr parse(String value) {
            try {
                String[] parts = value.split("/", -1);
                if (parts.length != 2 || !parts[0].matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException();
                byte[] network = InetAddress.getByName(parts[0]).getAddress();
                int prefix = Integer.parseInt(parts[1]);
                if (prefix < 0 || prefix > network.length * 8) throw new IllegalArgumentException();
                return new Cidr(network, prefix);
            } catch (Exception exception) {
                throw new IllegalArgumentException("TARGET_DB_ALLOWED_CIDRS contains an invalid CIDR: " + value, exception);
            }
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) return false;
            int fullBytes = prefixBits / 8;
            int remaining = prefixBits % 8;
            for (int i = 0; i < fullBytes; i++) if (candidate[i] != network[i]) return false;
            if (remaining == 0) return true;
            int mask = 0xFF << (8 - remaining);
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
