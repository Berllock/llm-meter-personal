package com.llmeter.personal.provider.antigravity.service.reader;

import com.llmeter.personal.provider.antigravity.domain.AntigravityIdeEndpoint;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class AntigravityIdeQuotaClientTest {
    private HttpServer server;
    private AntigravityIdeEndpoint endpoint;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        endpoint = new AntigravityIdeEndpoint(42,Instant.EPOCH,server.getAddress().getPort(),"PRIVATE_SYNTHETIC_TOKEN");
    }
    @AfterEach void stop() { server.stop(0); }
    // Validates the fixed Connect route, POST read request, private header and bounded JSON response.
    @Test void shouldUseOnlyTheAuthenticatedQuotaRead() {
        final var details = new AtomicReference<String>();
        server.createContext(AntigravityIdeQuotaClient.ROUTE, exchange -> {
            details.set(exchange.getRequestMethod()+":"+exchange.getRequestHeaders().getFirst("x-codeium-csrf-token")+":"+new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            final var bytes="{\"response\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,bytes.length);
            try(final var out=exchange.getResponseBody()) { out.write(bytes); }
        });server.start();
        assertThat(new AntigravityIdeQuotaClient().read(endpoint)).asString(StandardCharsets.UTF_8).contains("response");
        assertThat(details.get()).isEqualTo("POST:PRIVATE_SYNTHETIC_TOKEN:{\"forceRefresh\":true}");
    }
    // Validates redirects cannot forward the connection token to any alternate route or server.
    @Test void shouldNotFollowRedirects() {
        final var redirected = new AtomicInteger();
        server.createContext(AntigravityIdeQuotaClient.ROUTE, exchange -> { exchange.getResponseHeaders().set("Location","/elsewhere");exchange.sendResponseHeaders(302,-1);exchange.close(); });
        server.createContext("/elsewhere", exchange -> { redirected.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close(); });server.start();
        assertThatThrownBy(() -> new AntigravityIdeQuotaClient().read(endpoint)).isInstanceOf(ProviderCollectionException.class);
        assertThat(redirected.get()).isZero();
    }
    // Validates a stalled response is cancelled within the request deadline without leaking connection details.
    @Test void shouldBoundRequestTime() {
        server.createContext(AntigravityIdeQuotaClient.ROUTE, exchange -> { try {Thread.sleep(300);}catch(final InterruptedException e){Thread.currentThread().interrupt();}exchange.close(); });server.start();
        assertThatThrownBy(() -> new AntigravityIdeQuotaClient(Duration.ofMillis(50)).read(endpoint)).isInstanceOf(ProviderCollectionException.class).hasMessageNotContaining("PRIVATE_SYNTHETIC_TOKEN");
    }
    // Validates response streaming is limited before an unbounded body is retained.
    @Test void shouldBoundResponseBytes() {
        server.createContext(AntigravityIdeQuotaClient.ROUTE, exchange -> {
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,2_000_001);
            try(final var out=exchange.getResponseBody()){out.write(new byte[2_000_001]);}catch(final IOException ignored){}
        });server.start();
        assertThatThrownBy(() -> new AntigravityIdeQuotaClient().read(endpoint)).isInstanceOf(ProviderCollectionException.class);
    }
}
