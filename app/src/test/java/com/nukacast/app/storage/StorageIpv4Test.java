package com.nukacast.app.storage;

import org.junit.Test;

import java.net.InetAddress;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public final class StorageIpv4Test {
    @Test
    public void replacesSmbHostnameWithAnIpv4Literal() throws Exception {
        String resolved = StorageLibrary.ipv4SmbUri("smb://nas.local/media/", Arrays.asList(
                InetAddress.getByName("2001:db8::1"),
                InetAddress.getByName("192.0.2.25")));

        assertEquals("smb://192.0.2.25/media/", resolved);
    }
}
