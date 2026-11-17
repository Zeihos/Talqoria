package ru.talqoria.core;

import java.net.URI;
import java.util.Locale;

/** Prevent accidental bearer-token disclosure to an unencrypted remote endpoint. */
public final class BridgeEndpointPolicy {
    private BridgeEndpointPolicy() {}

    public static URI parse(String endpoint) {
        if (endpoint == null || endpoint.isBlank() || endpoint.length() > 2048)
            throw new IllegalArgumentException("Empty/oversized integration endpoint");
        URI uri = URI.create(endpoint);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || uri.getRawUserInfo() != null ||
                uri.getRawQuery() != null || uri.getRawFragment() != null ||
                !"/api/internal/heartbeat".equals(uri.getRawPath()))
            throw new IllegalArgumentException("Expected an absolute /api/internal/heartbeat URL without credentials/query/fragment");
        String lower = host.toLowerCase(Locale.ROOT);
        boolean local = lower.equals("localhost") || lower.equals("127.0.0.1") || lower.equals("::1") || lower.equals("[::1]");
        if (!"https".equalsIgnoreCase(scheme) && !(local && "http".equalsIgnoreCase(scheme)))
            throw new IllegalArgumentException("Bearer token requires HTTPS except localhost/loopback");
        if (uri.getPort() == 0 || uri.getPort() > 65535)
            throw new IllegalArgumentException("Invalid bridge port");
        return uri;
    }
}
