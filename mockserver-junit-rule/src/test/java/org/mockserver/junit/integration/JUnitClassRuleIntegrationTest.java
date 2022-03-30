package org.mockserver.junit.integration;

import org.junit.Before;
import org.junit.ClassRule;
import org.mockserver.junit.MockServerRule;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.PortFactory;
import org.mockserver.testing.integration.mock.AbstractBasicMockingSameJVMIntegrationTest;

import static org.slf4j.event.Level.WARN;

/**
 * @author jamesdbloom
 */
public class JUnitClassRuleIntegrationTest extends AbstractBasicMockingSameJVMIntegrationTest {

    // used fixed port for rule for all tests to ensure MockServer has been fully shutdown between each test
    private static final int MOCK_SERVER_PORT = PortFactory.findFreePort();

    @ClassRule
    public static final MockServerRule mockServerRule = new MockServerRule(JUnitClassRuleIntegrationTest.class, MOCK_SERVER_PORT);

    @Before
    @Override
    public void resetServer() {
        try {
            mockServerRule.getClient().reset();
            if (insecureEchoServer != null) {
                insecureEchoServer.clear();
            }
            if (secureEchoServer != null) {
                secureEchoServer.clear();
            }
        } catch (Throwable throwable) {
            if (MockServerLogger.isEnabled(WARN)) {
                MOCK_SERVER_LOGGER.logEvent(
                    new LogEntry()
                        .setLogLevel(WARN)
                        .setMessageFormat("exception while resetting - " + throwable.getMessage())
                        .setThrowable(throwable)
                );
            }
        }
    }

    @Override
    public int getServerPort() {
        return mockServerRule.getPort();
    }

}
