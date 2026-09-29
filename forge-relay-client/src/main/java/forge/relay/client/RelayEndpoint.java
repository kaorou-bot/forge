package forge.relay.client;

/** Network location and transport security mode of the central lobby/relay service. */
public record RelayEndpoint(String host, int port, boolean tls) {
    public static final String COMMUNITY_ADDRESS = "tls://play.mtg-forge-kaorou.vip:443";

    /** No DNS lookup here: editing an address must work offline, including on Android. */
    public static RelayEndpoint parse(String address) {
        try {
            String value = address == null || address.isBlank() ? COMMUNITY_ADDRESS : address.trim();
            java.net.URI uri = new java.net.URI(value.contains("://") ? value : "tls://" + value);
            boolean secure = "tls".equalsIgnoreCase(uri.getScheme());
            if (!secure && !"tcp".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException();
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty())) {
                throw new IllegalArgumentException();
            }
            if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
            // Unencrypted relay connections are only an explicit local development option.
            if (!secure && !isLoopback(host)) throw new IllegalArgumentException();
            return new RelayEndpoint(host, uri.getPort() == -1 ? (secure ? 443 : 36744) : uri.getPort(), secure);
        } catch (java.net.URISyntaxException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Use tls://hostname:port (default 443); tcp:// is loopback-only", e);
        }
    }

    public static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    public String address() {
        return (tls ? "tls://" : "tcp://") + (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
    }

    public RelayEndpoint(String host, int port) {
        this(host, port, false);
    }

    public RelayEndpoint {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Relay host is required");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Invalid relay port " + port);
        }
    }
}
