package com.nukacast.app.net;

import org.junit.Test;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class HttpStackTest {
    @Test
    public void keepsOnlyIpv4Addresses() throws Exception {
        InetAddress ipv6 = InetAddress.getByAddress(new byte[16]);
        InetAddress ipv4 = InetAddress.getByAddress(new byte[] {1, 1, 1, 1});

        List<InetAddress> result = HttpStack.ipv4Only(
                Arrays.asList(ipv6, ipv4), "example.com");

        assertEquals(1, result.size());
        assertEquals(ipv4, result.get(0));
    }
}
