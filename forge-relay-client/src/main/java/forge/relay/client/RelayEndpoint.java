package forge.relay.client;

/** Network location and transport security mode of the central lobby/relay service. */
public record RelayEndpoint(String host, int port, boolean tls) {
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
