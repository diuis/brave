package brave.features.async;

import brave.Span;
import brave.Tracer;
import brave.propagation.Propagation;
import brave.propagation.TraceContextOrSamplingFlags;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import zipkin.Endpoint;
import zipkin.storage.InMemoryStorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static zipkin.Constants.CLIENT_SEND;
import static zipkin.Constants.SERVER_RECV;

/**
 * This is an example of a one-way span, which is possible by use of the {@link Span#flush()}
 * operator.
 */
public class OneWaySpanTest {
  @Rule public MockWebServer server = new MockWebServer();

  InMemoryStorage storage = new InMemoryStorage();

  /** Use different tracers for client and server as usually they are on different hosts. */
  Tracer clientTracer = Tracer.newBuilder()
      .localEndpoint(Endpoint.builder().serviceName("client").build())
      .reporter(s -> storage.spanConsumer().accept(Collections.singletonList(s)))
      .build();
  Tracer serverTracer = Tracer.newBuilder()
      .localEndpoint(Endpoint.builder().serviceName("server").build())
      .reporter(s -> storage.spanConsumer().accept(Collections.singletonList(s)))
      .build();

  CountDownLatch flushedIncomingRequest = new CountDownLatch(1);

  @Before public void setup() {
    server.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest recordedRequest) {
        // pull the context out of the incoming request
        TraceContextOrSamplingFlags result =
            Propagation.B3_STRING.extractor(RecordedRequest::getHeader).extract(recordedRequest);

        // in real life, we'd guard result.context was set and start a new trace if not
        serverTracer.joinSpan(result.context())
            .name(recordedRequest.getMethod())
            .annotate(SERVER_RECV).flush(); // record the timestamp of the server receive and flush

        flushedIncomingRequest.countDown();
        // eventhough the client doesn't read the response, we return one
        return new MockResponse();
      }
    });
  }

  @Test
  public void startWithOneTracerAndStopWithAnother() throws Exception {
    // start a new span representing a request
    Span span = clientTracer.newTrace();

    // inject the trace context into the request
    Request.Builder request = new Request.Builder().url(server.url("/"));
    Propagation.B3_STRING.injector(Request.Builder::addHeader).inject(span.context(), request);

    // fire off the request asynchronously, totally dropping any response
    new OkHttpClient().newCall(request.build()).enqueue(mock(Callback.class));
    span.annotate(CLIENT_SEND).flush(); // record the timestamp of the client send and flush

    // block on the server handling the request, so we can run assertions
    flushedIncomingRequest.await();

    // zipkin doesn't backfill timestamp and duration when storing raw spans
    List<zipkin.Span> spans = storage.spanStore().getRawTrace(span.context().traceId());
    assertThat(spans).flatExtracting(s -> s.timestamp, s -> s.duration)
        .allSatisfy(u -> assertThat(u).isNull());

    // check that the client send arrived first
    zipkin.Span clientSpan = spans.get(0);
    assertThat(clientSpan.name).isEmpty();
    assertThat(clientSpan.annotations)
        .extracting(a -> a.value, a -> a.endpoint.serviceName)
        .containsExactly(tuple(CLIENT_SEND, "client"));

    // check that the server receive arrived last
    zipkin.Span serverSpan = spans.get(1);
    assertThat(serverSpan.name).isEqualTo("get");
    assertThat(serverSpan.annotations)
        .extracting(a -> a.value, a -> a.endpoint.serviceName)
        .containsExactly(tuple(SERVER_RECV, "server"));

    // Zipkin will backfill the timestamp and duration on normal getTrace used by the UI
    assertThat(storage.spanStore().getTrace(clientSpan.traceId))
        .flatExtracting(s -> s.timestamp, s -> s.duration)
        .allSatisfy(u -> assertThat(u).isNotNull());
  }
}
