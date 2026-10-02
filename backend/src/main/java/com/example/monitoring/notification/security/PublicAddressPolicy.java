package com.example.monitoring.notification.security;

import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

@Component
public final class PublicAddressPolicy {
    public InetAddress[] requirePublic(String host, InetAddress[] addresses) {
        if (host == null || host.isBlank() || addresses == null || addresses.length == 0) {
            throw invalid();
        }
        InetAddress[] copy = addresses.clone();
        for (InetAddress address : copy) {
            requirePublic(address);
        }
        return copy;
    }

    public void requirePublic(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            throw invalid();
        }
        if (address instanceof Inet4Address) {
            requirePublicIpv4(address.getAddress());
            return;
        }
        if (address instanceof Inet6Address) {
            requirePublicIpv6(address.getAddress());
            return;
        }
        throw invalid();
    }

    private void requirePublicIpv4(byte[] value) {
        int first = unsigned(value[0]);
        int second = unsigned(value[1]);
        int third = unsigned(value[2]);
        int fourth = unsigned(value[3]);
        boolean denied = first == 0 || first == 10 || first == 127
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 0 && third == 0)
                || (first == 192 && second == 0 && third == 2)
                || (first == 192 && second == 31 && third == 196)
                || (first == 192 && second == 52 && third == 193)
                || (first == 192 && second == 88 && third == 99)
                || (first == 192 && second == 168)
                || (first == 192 && second == 175 && third == 48)
                || (first == 198 && (second == 18 || second == 19))
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113)
                || first >= 224
                || (first == 168 && second == 63 && third == 129 && fourth == 16);
        if (denied) {
            throw invalid();
        }
    }

    private void requirePublicIpv6(byte[] value) {
        boolean globalUnicast = (unsigned(value[0]) & 0xe0) == 0x20;
        boolean ianaSpecial = unsigned(value[0]) == 0x20 && unsigned(value[1]) == 0x01
                && (unsigned(value[2]) & 0xfe) == 0;
        boolean documentation = unsigned(value[0]) == 0x20 && unsigned(value[1]) == 0x01
                && unsigned(value[2]) == 0x0d && unsigned(value[3]) == 0xb8;
        boolean sixToFour = unsigned(value[0]) == 0x20 && unsigned(value[1]) == 0x02;
        boolean oldSixBone = unsigned(value[0]) == 0x3f && unsigned(value[1]) == 0xfe;
        if (!globalUnicast || ianaSpecial || documentation || sixToFour || oldSixBone) {
            throw invalid();
        }
    }

    private int unsigned(byte value) {
        return value & 0xff;
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("Resolved notification destination is not public.");
    }
}
