package brave;

import brave.propagation.TraceContext;
import zipkin2.Endpoint;

/**
 * Used to model the latency of an operation.
 *
 * Here's a typical example of synchronous tracing from perspective of the span:
 * <pre>{@code
 * // Note span methods chain. Explicitly start the span when ready.
 * Span span = tracer.nextSpan().name("encode").start();
 * // A span is not responsible for making itself current (scoped); the tracer is
 * try (SpanInScope ws = tracer.withSpanInScope(span)) {
 *   return encoder.encode();
 * } catch (RuntimeException | Error e) {
 *   span.error(e); // Unless you handle exceptions, you might not know the operation failed!
 *   throw e;
 * } finally {
 *   span.finish(); // finish - start = the duration of the operation in microseconds
 * }
 * }</pre>
 *
 * <p>This captures duration of {@link #start()} until {@link #finish()} is called.
 *
 * <p>Note: When only tracing in-process operations, consider {@link ScopedSpan}: a simpler api.
 */
// Design note: this does not require a builder as the span is mutable anyway. Having a single
// mutation interface is less code to maintain. Those looking to prepare a span before starting it
// can simply call start when they are ready.
// BRAVE5: do not inherit SpanCustomizer, rather just return it. This will prevent accidentally
// leaking lifecycle methods
public abstract class Span implements SpanCustomizer {
  public enum Kind {
    CLIENT,
    SERVER,
    /**
     * When present, {@link #start()} is the moment a producer sent a message to a destination. A
     * duration between {@link #start()} and {@link #finish()} may imply batching delay. {@link
     * #remoteEndpoint(Endpoint)} indicates the destination, such as a broker.
     *
     * <p>Unlike {@link #CLIENT}, messaging spans never share a span ID. For example, the {@link
     * #CONSUMER} of the same message has {@link TraceContext#parentId()} set to this span's {@link
     * TraceContext#spanId()}.
     */
    PRODUCER,
    /**
     * When present, {@link #start()} is the moment a consumer received a message from an origin. A
     * duration between {@link #start()} and {@link #finish()} may imply a processing backlog. while
     * {@link #remoteEndpoint(Endpoint)} indicates the origin, such as a broker.
     *
     * <p>Unlike {@link #SERVER}, messaging spans never share a span ID. For example, the {@link
     * #PRODUCER} of this message is the {@link TraceContext#parentId()} of this span.
     */
    CONSUMER
  }

  /**
   * When true, no recording is done and nothing is reported to zipkin. However, this span should
   * still be injected into outgoing requests. Use this flag to avoid performing expensive
   * computation.
   */
  public abstract boolean isNoop();

  public abstract TraceContext context();

  /** Returns a customizer appropriate for the current span. Prefer this when invoking user code */
  public abstract SpanCustomizer customizer();

  /**
   * Starts the span with an implicit timestamp.
   *
   * <p>Spans can be modified before calling start. For example, you can add tags to the span and
   * set its name without lock contention.
   */
  public abstract Span start();

  /**
   * Like {@link #start()}, except with a given timestamp in microseconds.
   *
   * <p>Take extreme care with this feature as it is easy to have incorrect timestamps. If you must
   * use this, generate the timestamp using {@link Tracing#clock(TraceContext)}.
   */
  public abstract Span start(long timestamp);

  /** {@inheritDoc} */
  @Override public abstract Span name(String name);

  /**
   * The kind of span is optional. When set, it affects how a span is reported. For example, if the
   * kind is {@link Kind#SERVER}, the span's start timestamp is implicitly annotated as "sr" and
   * that plus its duration as "ss".
   */
  public abstract Span kind(Kind kind);

  /** {@inheritDoc} */
  @Override public abstract Span annotate(String value);

  /**
   * Like {@link #annotate(String)}, except with a given timestamp in microseconds.
   *
   * <p>Take extreme care with this feature as it is easy to have incorrect timestamps. If you must
   * use this, generate the timestamp using {@link Tracing#clock(TraceContext)}.
   */
  @Override public abstract Span annotate(long timestamp, String value);

  /** {@inheritDoc} */
  @Override public abstract Span tag(String key, String value);

  /** Adds tags depending on the configured {@link Tracing#errorParser() error parser} */
  // Design note: <T extends Throwable> T error(T throwable) is tempting but this doesn't work in
  // multi-catch. In practice, you should always at least catch RuntimeException and Error.
  public abstract Span error(Throwable throwable);

  /**
   * @deprecated use {@link #remoteEndpoint(Endpoint)}, possibly with {@link zipkin.Endpoint#toV2()}
   */
  @Deprecated
  public final Span remoteEndpoint(zipkin.Endpoint endpoint) {
    if (isNoop()) return this;
    return remoteEndpoint(endpoint.toV2());
  }

  /**
   * For a client span, this would be the server's address.
   *
   * <p>It is often expensive to derive a remote address: always check {@link #isNoop()} first!
   */
  public abstract Span remoteEndpoint(Endpoint endpoint);

  /** Reports the span complete, assigning the most precise duration possible. */
  public abstract void finish();

  /** Throws away the current span without reporting it. */
  public abstract void abandon();

  /**
   * Like {@link #finish()}, except with a given timestamp in microseconds.
   *
   * <p>{@link zipkin.Span#duration Zipkin's span duration} is derived by subtracting the start
   * timestamp from this, and set when appropriate.
   *
   * <p>Take extreme care with this feature as it is easy to have incorrect timestamps. If you must
   * use this, generate the timestamp using {@link Tracing#clock(TraceContext)}.
   */
  // Design note: This differs from Brave 3's LocalTracer which completes with a given duration.
  // This was changed for a few use cases.
  // * Finishing a one-way span on another host https://github.com/openzipkin/zipkin/issues/1243
  //   * The other host will not be able to read the start timestamp, so can't calculate duration
  // * Consistency in Api: All units and measures are epoch microseconds
  //   * This can reduce accidents where people use duration when they mean a timestamp
  // * Parity with OpenTracing
  //   * OpenTracing close spans like this, and this makes a Brave bridge stateless wrt timestamps
  // Design note: This does not implement Closeable (or AutoCloseable)
  // * the try-with-resources pattern is be reserved for attaching a span to a context.
  public abstract void finish(long timestamp);

  /**
   * Reports the span, even if unfinished. Most users will not call this method.
   *
   * <p>This primarily supports two use cases: one-way spans and orphaned spans. For example, a
   * one-way span can be modeled as a span where one tracer calls start and another calls finish. In
   * order to report that span from its origin, flush must be called.
   *
   * <p>Another example is where a user didn't call finish within a deadline or before a shutdown
   * occurs. By flushing, you can report what was in progress.
   */
  // Design note: This does not implement Flushable
  // * a span should not be routinely flushed, only when it has finished, or we don't believe this
  //   tracer will finish it.
  public abstract void flush();

  Span() { // intentionally hidden constructor
  }
}
