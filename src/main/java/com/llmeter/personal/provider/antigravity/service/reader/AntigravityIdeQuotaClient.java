package com.llmeter.personal.provider.antigravity.service.reader;

import com.llmeter.personal.provider.antigravity.domain.AntigravityIdeEndpoint;
import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** A single fixed read-only Connect RPC on literal IPv4 loopback, without redirects or system proxies. */
public final class AntigravityIdeQuotaClient {
    public static final String ROUTE = "/exa.language_server_pb.LanguageServerService/RetrieveUserQuotaSummary";
    private static final int MAX_BYTES = 2_000_000;
    private final Duration timeout;
    private final HttpClient client;
    public AntigravityIdeQuotaClient() { this(Duration.ofSeconds(10)); }
    public AntigravityIdeQuotaClient(final Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(final URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(final URI uri, final SocketAddress address, final IOException failure) { }
                }).build();
    }
    public byte[] read(final AntigravityIdeEndpoint endpoint) {
        final var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + endpoint.port() + ROUTE))
                .timeout(timeout).header("Content-Type", "application/json").header("Accept", "application/json")
                .header("Connect-Protocol-Version", "1").header("x-codeium-csrf-token", endpoint.token())
                .POST(HttpRequest.BodyPublishers.ofString("{\"forceRefresh\":true}")).build();
        final var pending = client.sendAsync(request, (final var response) -> new LimitedBody());
        try {
            final var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type").orElse("").startsWith("application/json")) throw failure();
            return response.body();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt(); throw failure();
        } catch (final Exception e) { throw failure(); }
        finally { pending.cancel(true); }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long bytes;
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(final Flow.Subscription subscription) { this.subscription = subscription; delegate.onSubscribe(subscription); }
        @Override public void onNext(final List<ByteBuffer> buffers) {
            for (final var buffer : buffers) bytes += buffer.remaining();
            if (bytes > MAX_BYTES) { subscription.cancel(); delegate.onError(new IOException("Quota response exceeds limit")); }
            else delegate.onNext(buffers);
        }
        @Override public void onError(final Throwable error) { delegate.onError(error); }
        @Override public void onComplete() { delegate.onComplete(); }
    }
    private ProviderCollectionException failure() {
        return new ProviderCollectionException(ProviderCollectionFailure.READ_FAILURE, "Could not query Antigravity IDE quota safely");
    }
}
