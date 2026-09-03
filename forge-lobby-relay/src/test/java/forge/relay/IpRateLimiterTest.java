package forge.relay;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

public class IpRateLimiterTest {
    @Test
    public void limitsEachAddressAndActionIndependently() throws Exception {
        IpRateLimiter limiter = new IpRateLimiter(2, 3, 4, TimeUnit.MINUTES.toNanos(1));
        InetAddress first = InetAddress.getByName("192.0.2.1");
        InetAddress second = InetAddress.getByName("192.0.2.2");

        Assert.assertTrue(limiter.allow(first, IpRateLimiter.Action.REGISTER));
        Assert.assertTrue(limiter.allow(first, IpRateLimiter.Action.REGISTER));
        Assert.assertFalse(limiter.allow(first, IpRateLimiter.Action.REGISTER));
        Assert.assertTrue(limiter.allow(first, IpRateLimiter.Action.LIST));
        Assert.assertTrue(limiter.allow(second, IpRateLimiter.Action.REGISTER));
    }

    @Test
    public void opensANewWindowAfterExpiry() throws Exception {
        IpRateLimiter limiter = new IpRateLimiter(1, 1, 1, TimeUnit.MILLISECONDS.toNanos(100));
        InetAddress address = InetAddress.getByName("192.0.2.3");

        Assert.assertTrue(limiter.allow(address, IpRateLimiter.Action.JOIN));
        Assert.assertFalse(limiter.allow(address, IpRateLimiter.Action.JOIN));
        Thread.sleep(150);
        Assert.assertTrue(limiter.allow(address, IpRateLimiter.Action.JOIN));
    }
}
