package org.openkaryon.karyoforge.database.driver;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Loaded in the PostgreSQL driver lease so each tunnel owns its sockets. */
public final class ForwardSocketFactory extends SocketFactory {
    private static final Set<Socket> active = new HashSet<>();
    private static BooleanSupplier owner;
    private static int endpoint;
    private static boolean closed;

    public ForwardSocketFactory(Properties properties) {}

    public static synchronized void configure(int port, BooleanSupplier live) {
        if (owner != null || closed || port < 1 || port > 65535) throw new IllegalArgumentException("Invalid SSH socket owner.");
        endpoint = port;
        owner = live;
    }

    @Override public Socket createSocket() throws IOException {
        Socket socket;
        InetSocketAddress address;
        synchronized (ForwardSocketFactory.class) {
            checkOwner();
            active.removeIf(Socket::isClosed);
            if (active.size() >= 32) throw new IOException("The SSH socket limit was reached.");
            socket = new Socket(Proxy.NO_PROXY);
            active.add(socket);
            address = new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), endpoint);
        }
        try {
            socket.connect(address, 10_000);
            synchronized (ForwardSocketFactory.class) { checkOwner(); }
            return socket;
        } catch (IOException | RuntimeException failure) {
            try {
                socket.close();
            } finally {
                synchronized (ForwardSocketFactory.class) {
                    if (socket.isClosed()) active.remove(socket);
                }
            }
            throw new IOException("Could not connect through the owned PostgreSQL SSH tunnel.");
        }
    }

    private static void checkOwner() throws IOException {
        if (closed || owner == null || !owner.getAsBoolean()) throw new IOException("The SSH forwarding process is unavailable.");
    }

    @Override public Socket createSocket(String host, int port) throws IOException { return createSocket(); }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException { return createSocket(); }
    @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException { return createSocket(); }
    @Override public Socket createSocket(InetAddress host, int port, InetAddress localHost, int localPort) throws IOException { return createSocket(); }

    public static synchronized void closeOwned() throws IOException {
        closed = true;
        owner = null;
        boolean failed = false;
        for (var socket : active) {
            try {
                socket.close();
            } catch (IOException unavailable) {
                failed = true;
            }
        }
        active.removeIf(Socket::isClosed);
        if (failed || !active.isEmpty()) throw new IOException("Could not confirm closure of the owned SSH sockets.");
    }
}
