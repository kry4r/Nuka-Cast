package com.nukacast.app.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

final class Tls12SocketFactory extends SSLSocketFactory {
    private final SSLSocketFactory delegate;
    private final int sdk;

    Tls12SocketFactory(SSLSocketFactory delegate, int sdk) {
        this.delegate = delegate;
        this.sdk = sdk;
    }

    @Override public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override public Socket createSocket() throws IOException {
        return configure(delegate.createSocket());
    }

    @Override public Socket createSocket(Socket socket, String host, int port, boolean autoClose)
            throws IOException {
        return configure(delegate.createSocket(socket, host, port, autoClose));
    }

    @Override public Socket createSocket(String host, int port) throws IOException {
        return configure(delegate.createSocket(host, port));
    }

    @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
            throws IOException {
        return configure(delegate.createSocket(host, port, localHost, localPort));
    }

    @Override public Socket createSocket(InetAddress host, int port) throws IOException {
        return configure(delegate.createSocket(host, port));
    }

    @Override public Socket createSocket(InetAddress address, int port,
                                         InetAddress localAddress, int localPort) throws IOException {
        return configure(delegate.createSocket(address, port, localAddress, localPort));
    }

    private Socket configure(Socket socket) {
        if (socket instanceof SSLSocket) {
            SSLSocket ssl = (SSLSocket) socket;
            ssl.setEnabledProtocols(protocolsFor(
                    sdk, ssl.getSupportedProtocols(), ssl.getEnabledProtocols()));
        }
        return socket;
    }

    static String[] protocolsFor(int sdk, String[] supported, String[] enabled) {
        if (sdk < 16 || sdk >= 22 || !contains(supported, "TLSv1.2")) {
            return enabled;
        }
        return new String[] {"TLSv1.2"};
    }

    private static boolean contains(String[] values, String wanted) {
        if (values == null) return false;
        for (String value : values) if (wanted.equals(value)) return true;
        return false;
    }
}
