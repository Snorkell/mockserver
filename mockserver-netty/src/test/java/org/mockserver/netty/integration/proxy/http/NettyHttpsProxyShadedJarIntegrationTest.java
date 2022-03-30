package org.mockserver.netty.integration.proxy.http;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.util.EntityUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.echo.http.EchoServer;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpStatusCode;
import org.mockserver.netty.integration.ShadedJarRunner;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.socket.PortFactory;
import org.mockserver.socket.tls.KeyStoreFactory;
import org.mockserver.streams.IOStreamUtils;

import javax.net.ssl.SSLSocket;
import java.io.OutputStream;
import java.net.Socket;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static junit.framework.TestCase.assertEquals;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.test.Assert.assertContains;
import static org.mockserver.testing.tls.SSLSocketFactory.sslSocketFactory;
import static org.mockserver.verify.VerificationTimes.exactly;
import static org.slf4j.event.Level.ERROR;

/**
 * @author jamesdbloom
 */
public class NettyHttpsProxyShadedJarIntegrationTest {

    private static final int mockServerPort = PortFactory.findFreePort();
    private static EchoServer secureEchoServer;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void setupFixture() {
        mockServerClient = ShadedJarRunner.startServerUsingShadedJar(mockServerPort);

        System.setProperty("http.proxyHost", "127.0.0.1");
        System.setProperty("http.proxyPort", String.valueOf(mockServerPort));

        secureEchoServer = new EchoServer(true);
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(secureEchoServer);

        System.clearProperty("http.proxyHost");
        System.clearProperty("http.proxyPort");

        stopQuietly(mockServerClient);
    }

    @Before
    public void resetProxy() {
        mockServerClient.reset();
        secureEchoServer.mockServerEventLog().reset();
    }

    private static EventLoopGroup clientEventLoopGroup;

    @BeforeClass
    public static void startEventLoopGroup() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(NettyHttpsProxyShadedJarIntegrationTest.class.getSimpleName() + "-eventLoop"));
    }

    @AfterClass
    public static void stopEventLoopGroup() {
        clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
    }

    @Test
    public void shouldConnectToSecurePort() throws Exception {
        try {
            try (Socket socket = new Socket("127.0.0.1", mockServerPort)) {
                // given
                OutputStream output = socket.getOutputStream();

                // when
                output.write(("" +
                    "CONNECT 127.0.0.1:443 HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:" + secureEchoServer.getPort() + "\r\n" +
                    "\r\n"
                ).getBytes(UTF_8));
                output.flush();

                // then
                assertContains(IOStreamUtils.readInputStreamToString(socket), "HTTP/1.1 200 OK");
            }
        } catch (java.net.SocketException se) {
            new MockServerLogger().logEvent(
                new LogEntry()
                    .setLogLevel(ERROR)
                    .setMessageFormat("Port port " + mockServerPort)
                    .setThrowable(se)
            );
            throw se;
        }
    }

    @Test
    public void shouldConnectToSecurePortWithSecureConnectRequest() throws Exception {
        try {
            try (Socket socket = new Socket("127.0.0.1", mockServerPort)) {
                // Upgrade the socket to SSL
                try (SSLSocket sslSocket = sslSocketFactory().wrapSocket(socket)) {
                    // given
                    OutputStream output = sslSocket.getOutputStream();

                    // when
                    output.write(("" +
                        "CONNECT 127.0.0.1:443 HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:" + secureEchoServer.getPort() + "\r\n" +
                        "\r\n"
                    ).getBytes(UTF_8));
                    output.flush();

                    // then
                    assertContains(IOStreamUtils.readInputStreamToString(sslSocket), "HTTP/1.1 200 OK");
                }
            }
        } catch (java.net.SocketException se) {
            new MockServerLogger().logEvent(
                new LogEntry()
                    .setLogLevel(ERROR)
                    .setMessageFormat("Port port " + mockServerPort)
                    .setThrowable(se)
            );
            throw se;
        }
    }

    @Test
    public void shouldForwardRequestsToSecurePortUsingSocketDirectly() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", mockServerPort)) {
            // given
            OutputStream output = socket.getOutputStream();

            // when
            // - send CONNECT request
            output.write(("" +
                "CONNECT 127.0.0.1:" + secureEchoServer.getPort() + " HTTP/1.1\r\n" +
                "Host: 127.0.0.1:" + secureEchoServer.getPort() + "\r\n" +
                "\r\n"
            ).getBytes(UTF_8));
            output.flush();

            // - flush CONNECT response
            assertContains(IOStreamUtils.readInputStreamToString(socket), "HTTP/1.1 200 OK");

            // Upgrade the socket to SSL
            try (SSLSocket sslSocket = sslSocketFactory().wrapSocket(socket)) {

                output = sslSocket.getOutputStream();

                // - send GET request for headers only
                output.write(("" +
                    "GET /test_headers_only HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:" + secureEchoServer.getPort() + "\r\n" +
                    "X-Test: test_headers_only\r\n" +
                    "Connection: keep-alive\r\n" +
                    "\r\n"
                ).getBytes(UTF_8));
                output.flush();

                // then
                assertContains(IOStreamUtils.readInputStreamToString(sslSocket), "X-Test: test_headers_only");

                // - send GET request for headers and body
                output.write(("" +
                    "GET /test_headers_and_body HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:" + secureEchoServer.getPort() + "\r\n" +
                    "Content-Length: " + "an_example_body".getBytes(UTF_8).length + "\r\n" +
                    "X-Test: test_headers_and_body\r\n" +
                    "\r\n" +
                    "an_example_body"
                ).getBytes(UTF_8));
                output.flush();

                // then
                String response = IOStreamUtils.readInputStreamToString(sslSocket);
                assertContains(response, "X-Test: test_headers_and_body");
                assertContains(response, "an_example_body");

                // and
                mockServerClient.verify(
                    request()
                        .withMethod("GET")
                        .withPath("/test_headers_and_body")
                        .withBody("an_example_body"),
                    exactly(1)
                );
            }
        }
    }

    @Test
    public void shouldForwardRequestsToSecurePortUsingSocketDirectlyWithSecureConnectRequest() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", mockServerPort)) {

            // Upgrade the socket to SSL
            try (SSLSocket sslSocket = sslSocketFactory().wrapSocket(socket)) {

                // given
                OutputStream output = sslSocket.getOutputStream();

                // when
                // - send CONNECT request
                output.write(("" +
                    "CONNECT 127.0.0.1:" + secureEchoServer.getPort() + " HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:" + secureEchoServer.getPort() + "\r\n" +
                    "\r\n"
                ).getBytes(UTF_8));
                output.flush();

                // - flush CONNECT response
                assertContains(IOStreamUtils.readInputStreamToString(sslSocket), "HTTP/1.1 200 OK");

                // - send GET request for headers only
                output.write(("" +
                    "GET /test_headers_only HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:" + secureEchoServer.getPort() + "\r\n" +
                    "X-Test: test_headers_only\r\n" +
                    "Connection: keep-alive\r\n" +
                    "\r\n"
                ).getBytes(UTF_8));
                output.flush();

                // then
                assertContains(IOStreamUtils.readInputStreamToString(sslSocket), "X-Test: test_headers_only");

                // - send GET request for headers and body
                output.write(("" +
                    "GET /test_headers_and_body HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:" + secureEchoServer.getPort() + "\r\n" +
                    "Content-Length: " + "an_example_body".getBytes(UTF_8).length + "\r\n" +
                    "X-Test: test_headers_and_body\r\n" +
                    "\r\n" +
                    "an_example_body"
                ).getBytes(UTF_8));
                output.flush();

                // then
                String response = IOStreamUtils.readInputStreamToString(sslSocket);
                assertContains(response, "X-Test: test_headers_and_body");
                assertContains(response, "an_example_body");

                // and
                mockServerClient.verify(
                    request()
                        .withMethod("GET")
                        .withPath("/test_headers_and_body")
                        .withBody("an_example_body"),
                    exactly(1)
                );
            }
        }
    }

    @Test
    public void shouldForwardRequestsToSecurePortUsingHttpClientViaHTTP_CONNECT() throws Exception {
        // given
        HttpClient httpClient = HttpClients
            .custom()
            .setSSLSocketFactory(new SSLConnectionSocketFactory(new KeyStoreFactory(configuration(), new MockServerLogger()).sslContext(), NoopHostnameVerifier.INSTANCE))
            .setRoutePlanner(
                new DefaultProxyRoutePlanner(
                    new HttpHost(
                        System.getProperty("http.proxyHost"),
                        Integer.parseInt(System.getProperty("http.proxyPort"))
                    )
                )
            ).build();

        // when
        HttpPost request = new HttpPost(
            new URIBuilder()
                .setScheme("https")
                .setHost("127.0.0.1")
                .setPort(secureEchoServer.getPort())
                .setPath("/test_headers_and_body")
                .build()
        );
        request.setEntity(new StringEntity("an_example_body"));
        HttpResponse response = httpClient.execute(request);

        // then
        assertEquals(HttpStatusCode.OK_200.code(), response.getStatusLine().getStatusCode());
        assertEquals("an_example_body", new String(EntityUtils.toByteArray(response.getEntity()), UTF_8));

        // and
        mockServerClient.verify(
            request()
                .withPath("/test_headers_and_body")
                .withBody("an_example_body"),
            exactly(1)
        );
    }

    @Test
    public void shouldForwardRequestsToSecurePortAndUnknownPath() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", mockServerPort)) {
            // given
            OutputStream output = socket.getOutputStream();

            // when
            // - send CONNECT request
            output.write(("" +
                "CONNECT 127.0.0.1:443 HTTP/1.1\r\n" +
                "Host: 127.0.0.1:" + secureEchoServer.getPort() + "\r\n" +
                "\r\n"
            ).getBytes(UTF_8));
            output.flush();

            // - flush CONNECT response
            assertContains(IOStreamUtils.readInputStreamToString(socket), "HTTP/1.1 200 OK");

            // Upgrade the socket to SSL
            try (SSLSocket sslSocket = sslSocketFactory().wrapSocket(socket)) {

                // - send GET request
                output = sslSocket.getOutputStream();
                output.write(("" +
                    "GET /not_found HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:" + secureEchoServer.getPort() + "\r\n" +
                    "\r\n"
                ).getBytes(UTF_8));
                output.flush();

                // then
                assertContains(IOStreamUtils.readInputStreamToString(sslSocket), "HTTP/1.1 404 Not Found");

                // and
                mockServerClient.verify(
                    request()
                        .withMethod("GET")
                        .withPath("/not_found"),
                    exactly(1)
                );
            }
        }
    }
}
