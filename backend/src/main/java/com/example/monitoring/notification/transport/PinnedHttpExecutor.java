package com.example.monitoring.notification.transport;

import java.net.InetAddress;

public interface PinnedHttpExecutor {
    PinnedHttpsResponse execute(PinnedHttpsRequest request, InetAddress[] pinnedAddresses);
}
