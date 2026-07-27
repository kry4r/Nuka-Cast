package com.nukacast.app.airplay;

import java.net.DatagramPacket;
import java.net.InetAddress;

import javax.jmdns.impl.DNSIncoming;

import junit.framework.TestCase;

public final class JmDnsApi19Test extends TestCase {
    public void testAirPlayQueryParsesWithoutJavaUtilFunction() throws Exception {
        byte[] query = new byte[] {
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x01, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x08, 0x5f, 0x61, 0x69, 0x72, 0x70, 0x6c, 0x61, 0x79,
                0x04, 0x5f, 0x74, 0x63, 0x70,
                0x05, 0x6c, 0x6f, 0x63, 0x61, 0x6c,
                0x00, 0x00, 0x0c, 0x00, 0x01
        };
        DatagramPacket packet = new DatagramPacket(query, query.length,
                InetAddress.getByName("192.0.2.1"), 5353);

        DNSIncoming incoming = new DNSIncoming(packet);

        assertEquals(1, incoming.getQuestions().size());
    }
}
