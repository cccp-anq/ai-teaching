package com.aijiaoxue.videoreact.asr.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** HTTP body subscriber that aborts before an untrusted provider response can fill the heap. */
final class BoundedBodyHandlers {
    private BoundedBodyHandlers() { }

    static HttpResponse.BodyHandler<String> utf8(int maxBytes) {
        return ignored -> new Subscriber(maxBytes);
    }

    private static final class Subscriber implements HttpResponse.BodySubscriber<String> {
        private final int maxBytes;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private Subscriber(int maxBytes) { this.maxBytes = maxBytes; }
        @Override public CompletionStage<String> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            try {
                for (ByteBuffer buffer : buffers) {
                    if (output.size() + buffer.remaining() > maxBytes) {
                        subscription.cancel();
                        body.completeExceptionally(new IOException("provider response exceeds " + maxBytes + " bytes"));
                        return;
                    }
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    output.write(bytes, 0, bytes.length);
                }
                subscription.request(1);
            } catch (RuntimeException e) {
                subscription.cancel();
                body.completeExceptionally(e);
            }
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() {
            body.complete(output.toString(StandardCharsets.UTF_8));
        }
    }
}
