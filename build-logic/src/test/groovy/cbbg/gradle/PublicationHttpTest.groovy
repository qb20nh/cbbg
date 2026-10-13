package cbbg.gradle

import com.sun.net.httpserver.HttpServer
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test

import java.util.concurrent.atomic.AtomicInteger

import static org.junit.jupiter.api.Assertions.*

class PublicationHttpTest {
    private static void withResponses(List<Integer> statuses, Closure check) {
        AtomicInteger requests = new AtomicInteger()
        HttpServer server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/versions') { exchange ->
            assertEquals('GET', exchange.requestMethod)
            assertEquals('fixture-token', exchange.requestHeaders.getFirst('X-Api-Token'))
            int index = requests.getAndIncrement()
            int status = statuses[Math.min(index, statuses.size() - 1)]
            byte[] body = (status == 200 ? '[{"id":26}]' : 'Unavailable').getBytes('UTF-8')
            exchange.sendResponseHeaders(status, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
            exchange.close()
        }
        server.start()
        try {
            check.call('http://127.0.0.1:' + server.address.port + '/versions', requests)
        } finally {
            server.stop(0)
        }
    }

    @Test
    void temporaryServerErrorsRetryTheGetWithItsHeaders() {
        withResponses([503, 502, 200]) { url, requests ->
            assertEquals([[id: 26]], Publication.request(null, url, ['X-Api-Token': 'fixture-token']))
            assertEquals(3, requests.get())
        }
    }

    @Test
    void persistentServerErrorsStopAfterThreeAttempts() {
        withResponses([504]) { url, requests ->
            GradleException error = assertThrows(GradleException) {
                Publication.request(null, url, ['X-Api-Token': 'fixture-token'])
            }
            assertTrue(error.message.contains('HTTP 504'))
            assertTrue(error.message.contains('after 3 attempts'))
            assertEquals(3, requests.get())
        }
    }

    @Test
    void authenticationErrorsAreNotRetried() {
        withResponses([401]) { url, requests ->
            GradleException error = assertThrows(GradleException) {
                Publication.request(null, url, ['X-Api-Token': 'fixture-token'])
            }
            assertTrue(error.message.contains('HTTP 401'))
            assertEquals(1, requests.get())
        }
    }
}
