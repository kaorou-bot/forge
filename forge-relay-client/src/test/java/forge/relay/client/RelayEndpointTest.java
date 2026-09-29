package forge.relay.client;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class RelayEndpointTest {
    @Test public void defaultsAndRoundTrip() {
        assertEquals(RelayEndpoint.parse(null).address(), RelayEndpoint.COMMUNITY_ADDRESS);
        assertEquals(RelayEndpoint.parse(" ").address(), RelayEndpoint.COMMUNITY_ADDRESS);
        assertEquals(RelayEndpoint.parse(" relay.example.com "), new RelayEndpoint("relay.example.com", 443, true));
        assertEquals(RelayEndpoint.parse("relay.example.com:8443").port(), 8443);
        for (String value : new String[] {"tls://relay.example.com:8443", "tls://[::1]:443", "tcp://127.0.0.1:36744"}) {
            assertEquals(RelayEndpoint.parse(value).address(), value);
        }
        assertFalse(RelayEndpoint.parse("tcp://localhost").tls());
    }

    @DataProvider public Object[][] invalidAddresses() {
        return new Object[][] {{"https://relay.example.com"}, {"tls://host/path"}, {"tls://user@host"},
                {"tls://host?x=1"}, {"tls://host#x"}, {"host:0"}, {"host:65536"}, {"host:no"},
                {"tcp://relay.example.com"}, {"tcp://192.168.1.1:36744"}, {"bad host"}, {"tls://"}, {"host:-1"}};
    }

    @Test(dataProvider = "invalidAddresses", expectedExceptions = IllegalArgumentException.class)
    public void rejectsInvalidOrInsecureAddresses(String address) { RelayEndpoint.parse(address); }
}
