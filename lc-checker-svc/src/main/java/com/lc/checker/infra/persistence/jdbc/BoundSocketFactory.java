package com.lc.checker.infra.persistence.jdbc;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import javax.net.SocketFactory;

/**
 * JDBC {@link SocketFactory} that binds the local end of the TCP socket to a
 * specific source IP before connecting. Used by the postgres JDBC driver via
 * the URL params {@code socketFactory} + {@code socketFactoryArg}.
 *
 * <p><b>Why this exists.</b> On macOS dev machines that run a TUN-based proxy
 * (ClashX, Surge, Mihomo, V2RayU) and have multiple interfaces on the same
 * subnet, the kernel sometimes picks the proxy's CGNAT address (198.18.x.x)
 * as the local source IP for outbound connections. Replies from a real LAN
 * server (e.g. {@code 192.168.31.214}) then have no route back to the
 * CGNAT pseudo-interface, surfacing as
 * {@link java.net.NoRouteToHostException} from inside Hikari's pool init.
 *
 * <p>Pinning the local source IP at the socket layer makes the JDBC connection
 * deterministic — regardless of routing-table state, TUN comings/goings, or
 * which proxy app is currently running. The constructor argument is the
 * dotted-quad IP of the host's interface that has a route to the DB
 * (typically the LAN-facing en0/en1 IP).
 *
 * <p>When the constructor argument is blank/null, this factory degrades to
 * a no-op (just delegates to a plain {@link Socket}) — letting the kernel
 * choose the source IP as before. This keeps {@code DB_LOCAL_BIND=} (empty)
 * a safe default for environments without the proxy issue.
 *
 * <p>JDBC URL form:
 * <pre>
 *   jdbc:postgresql://192.168.31.214:5436/lc_checker
 *     ?socketFactory=com.lc.checker.infra.persistence.jdbc.BoundSocketFactory
 *     &amp;socketFactoryArg=192.168.31.201
 * </pre>
 *
 * <p>The postgres driver's contract for socket factories
 * (PgConnection / PGProperty.SOCKET_FACTORY) requires a public class with a
 * public single-String constructor.
 */
public class BoundSocketFactory extends SocketFactory {

    private final InetAddress localAddress;

    @SuppressWarnings("unused") // invoked reflectively by the postgres JDBC driver
    public BoundSocketFactory(String localBindIp) throws IOException {
        if (localBindIp == null || localBindIp.isBlank()) {
            this.localAddress = null;
        } else {
            this.localAddress = InetAddress.getByName(localBindIp.trim());
        }
    }

    @Override
    public Socket createSocket() throws IOException {
        Socket s = new Socket();
        if (localAddress != null) {
            s.bind(new InetSocketAddress(localAddress, 0));
        }
        return s;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket s = createSocket();
        s.connect(new InetSocketAddress(host, port));
        return s;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        // Caller-specified local addr wins over our pinned one — preserves SocketFactory contract.
        Socket s = new Socket();
        s.bind(new InetSocketAddress(localHost, localPort));
        s.connect(new InetSocketAddress(host, port));
        return s;
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        Socket s = createSocket();
        s.connect(new InetSocketAddress(host, port));
        return s;
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddr, int localPort) throws IOException {
        Socket s = new Socket();
        s.bind(new InetSocketAddress(localAddr, localPort));
        s.connect(new InetSocketAddress(address, port));
        return s;
    }
}
