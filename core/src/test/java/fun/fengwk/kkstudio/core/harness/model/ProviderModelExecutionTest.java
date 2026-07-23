package fun.fengwk.kkstudio.core.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelExecutionHandle;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelExecutionListener;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelExecutionRequest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Verifies frozen Provider execution, deadline fencing, callback bridging and cancellation. */
class ProviderModelExecutionTest {

  private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");
  private static final Duration CONFIGURED_TOTAL = Duration.ofSeconds(45L);
  private static final Duration CONFIGURED_IDLE = Duration.ofSeconds(3L);
  private static final ModelCallTimeoutPolicy CONFIGURED_POLICY =
      new ModelCallTimeoutPolicy(CONFIGURED_TOTAL, CONFIGURED_IDLE);
  private static final ProviderRequest FROZEN_REQUEST = frozenRequest();

  @Test
  void rejectsExpiredAndSubMillisecondDeadlinesBeforeSubmission() {
    try (Fixture fixture = new Fixture()) {
      ProviderException expired =
          assertThrows(ProviderException.class, () -> fixture.execute(NOW.minusMillis(1L)));
      ProviderException tooShort =
          assertThrows(ProviderException.class, () -> fixture.execute(NOW.plusNanos(999_999L)));

      assertEquals(ProviderErrorKind.TRANSIENT, expired.kind());
      assertEquals(ProviderErrorKind.TRANSIENT, tooShort.kind());
      assertEquals(0, fixture.openCount.get());
    }
  }

  @Test
  void capsTransportTotalByDurableDeadlineAndPreservesIdleTimeout() {
    try (Fixture fixture = new Fixture()) {
      fixture.execute(NOW.plusSeconds(10L));
      fixture.provider.awaitStarted();

      assertEquals(
          new ModelCallTimeoutPolicy(Duration.ofSeconds(10L), CONFIGURED_IDLE),
          fixture.openedPolicy.get());
    }
  }

  @Test
  void keepsConfiguredTransportTotalWhenDeadlineIsLater() {
    try (Fixture fixture = new Fixture()) {
      fixture.execute(NOW.plus(Duration.ofMinutes(10L)));
      fixture.provider.awaitStarted();

      assertEquals(CONFIGURED_POLICY, fixture.openedPolicy.get());
    }
  }

  @Test
  void returnsImmediatelyWhileProviderStartBlocks() {
    CountDownLatch release = new CountDownLatch(1);
    ControlledProvider provider = new ControlledProvider();
    provider.blockUntil(release);
    try (Fixture fixture = new Fixture(provider)) {
      long startedAt = System.nanoTime();
      ModelExecutionHandle handle = fixture.execute(NOW.plusSeconds(30L));
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

      assertNotNull(handle);
      assertTrue(elapsedMillis < 1_000L, "execute must not wait for Provider I/O");
      provider.awaitStarted();
      release.countDown();
      provider.awaitReturned();
    }
  }

  @Test
  void forwardsTheOriginalFrozenRequestAndKeepsAttemptIdentity() {
    try (Fixture fixture = new Fixture()) {
      ModelExecutionRequest request =
          new ModelExecutionRequest(7L, 3, FROZEN_REQUEST, NOW.plusSeconds(30L));

      fixture.subject.execute(request, fixture.listener);
      fixture.provider.awaitStarted();

      assertSame(FROZEN_REQUEST, fixture.provider.request.get());
      assertEquals("7:3", request.idempotencyKey());
    }
  }

  @Test
  void bridgesDeltasAndOneCompletionInProviderOrder() {
    try (Fixture fixture = new Fixture()) {
      fixture.execute(NOW.plusSeconds(30L));
      fixture.provider.awaitStarted();

      ProviderStreamEvent.TextDelta text = new ProviderStreamEvent.TextDelta("hello");
      ProviderStreamEvent.ThinkingDelta thinking =
          new ProviderStreamEvent.ThinkingDelta("thinking");
      fixture.provider.emit(text);
      fixture.provider.emit(thinking);
      fixture.provider.complete();

      assertEquals(List.of(text, thinking), fixture.listener.deltas);
      assertEquals(1, fixture.listener.completeCount.get());
      assertEquals(0, fixture.listener.errorCount.get());
      assertSame(fixture.provider.response, fixture.listener.response.get());
    }
  }

  @Test
  void preservesProviderFailureAndDropsAllCallbacksAfterTerminal() {
    try (Fixture fixture = new Fixture()) {
      fixture.execute(NOW.plusSeconds(30L));
      fixture.provider.awaitStarted();
      ProviderException failure =
          new ProviderException(ProviderErrorKind.TRANSIENT, "temporarily unavailable");

      fixture.provider.fail(failure);
      fixture.provider.emit(new ProviderStreamEvent.TextDelta("late"));
      fixture.provider.complete();

      assertSame(failure, fixture.listener.error.get());
      assertEquals(1, fixture.listener.errorCount.get());
      assertEquals(0, fixture.listener.completeCount.get());
      assertTrue(fixture.listener.deltas.isEmpty());
    }
  }

  @Test
  void doesNotTranslateACompletionListenerFailureIntoASecondTerminalCallback() {
    try (Fixture fixture = new Fixture()) {
      fixture.listener.throwOnComplete = true;
      fixture.execute(NOW.plusSeconds(30L));
      fixture.provider.awaitStarted();

      fixture.provider.complete();
      fixture.provider.fail(new ProviderException(ProviderErrorKind.TRANSIENT, "late"));

      assertEquals(1, fixture.listener.completeCount.get());
      assertEquals(0, fixture.listener.errorCount.get());
    }
  }

  @Test
  void translatesDeltaListenerFailureIntoOneInvalidRequestTerminal() {
    try (Fixture fixture = new Fixture()) {
      fixture.listener.throwOnDelta = true;
      fixture.execute(NOW.plusSeconds(30L));
      fixture.provider.awaitStarted();

      fixture.provider.emit(new ProviderStreamEvent.TextDelta("invalid"));
      fixture.provider.complete();

      assertEquals(1, fixture.listener.errorCount.get());
      assertEquals(ProviderErrorKind.INVALID_REQUEST, fixture.listener.error.get().kind());
      assertEquals(0, fixture.listener.completeCount.get());
    }
  }

  @Test
  void classifiesProviderCreationAndStartFailures() {
    IllegalStateException createFailure = new IllegalStateException("cannot create client");
    try (Fixture fixture =
        new Fixture(
            ignored -> {
              throw createFailure;
            })) {
      fixture.execute(NOW.plusSeconds(30L));
      fixture.listener.awaitError();

      assertEquals(ProviderErrorKind.INVALID_REQUEST, fixture.listener.error.get().kind());
      assertSame(createFailure, fixture.listener.error.get().getCause());
    }

    ControlledProvider provider = new ControlledProvider();
    provider.startFailure = new IllegalStateException("cannot start stream");
    try (Fixture fixture = new Fixture(provider)) {
      fixture.execute(NOW.plusSeconds(30L));
      fixture.listener.awaitError();

      assertEquals(ProviderErrorKind.INVALID_REQUEST, fixture.listener.error.get().kind());
      assertEquals("cannot start stream", fixture.listener.error.get().getMessage());
    }

    ControlledProvider noMessage = new ControlledProvider();
    noMessage.startFailure = new IllegalStateException();
    try (Fixture fixture = new Fixture(noMessage)) {
      fixture.execute(NOW.plusSeconds(30L));
      fixture.listener.awaitError();

      assertEquals("cannot start provider stream", fixture.listener.error.get().getMessage());
    }
  }

  @Test
  void preservesProviderExceptionThrownWhileStartingAndRejectsNullStream() {
    ProviderException original =
        new ProviderException(ProviderErrorKind.AUTHENTICATION, "invalid credential");
    ControlledProvider failing = new ControlledProvider();
    failing.startFailure = original;
    try (Fixture fixture = new Fixture(failing)) {
      fixture.execute(NOW.plusSeconds(30L));
      fixture.listener.awaitError();
      assertSame(original, fixture.listener.error.get());
    }

    ControlledProvider nullStream = new ControlledProvider();
    nullStream.returnNullStream = true;
    try (Fixture fixture = new Fixture(nullStream)) {
      fixture.execute(NOW.plusSeconds(30L));
      fixture.listener.awaitError();
      assertEquals(ProviderErrorKind.INVALID_REQUEST, fixture.listener.error.get().kind());
    }
  }

  @Test
  void rejectsNullOrInconsistentCallbackStreams() {
    try (Fixture fixture = new Fixture()) {
      fixture.execute(NOW.plusSeconds(30L));
      fixture.provider.awaitStarted();

      fixture.provider.handler.get().onEvent(new ProviderStreamEvent.TextDelta("x"), null);
      fixture.provider.complete();

      assertEquals(1, fixture.listener.errorCount.get());
      assertEquals(ProviderErrorKind.INVALID_REQUEST, fixture.listener.error.get().kind());
    }

    try (Fixture fixture = new Fixture()) {
      fixture.execute(NOW.plusSeconds(30L));
      fixture.provider.awaitStarted();
      fixture.provider.emit(new ProviderStreamEvent.TextDelta("first"));

      fixture
          .provider
          .handler
          .get()
          .onEvent(new ProviderStreamEvent.TextDelta("second"), new TestStream());

      assertEquals(1, fixture.listener.errorCount.get());
      assertEquals(ProviderErrorKind.INVALID_REQUEST, fixture.listener.error.get().kind());
    }
  }

  @Test
  void rejectsNullCallbackPayloadsAndIsolatesListenerTerminalFailures() {
    try (Fixture fixture = new Fixture()) {
      fixture.execute(NOW.plusSeconds(30L));
      fixture.provider.awaitStarted();
      fixture.provider.handler.get().onEvent(null, fixture.provider.stream);
      assertEquals(ProviderErrorKind.INVALID_REQUEST, fixture.listener.error.get().kind());
    }

    try (Fixture fixture = new Fixture()) {
      fixture.execute(NOW.plusSeconds(30L));
      fixture.provider.awaitStarted();
      fixture.provider.handler.get().onComplete(null, fixture.provider.stream);
      assertEquals(ProviderErrorKind.INVALID_REQUEST, fixture.listener.error.get().kind());
    }

    try (Fixture fixture = new Fixture()) {
      fixture.execute(NOW.plusSeconds(30L));
      fixture.provider.awaitStarted();
      fixture.provider.handler.get().onError(null, fixture.provider.stream);
      assertEquals(ProviderErrorKind.INVALID_REQUEST, fixture.listener.error.get().kind());
    }

    try (Fixture fixture = new Fixture()) {
      fixture.listener.throwOnError = true;
      fixture.execute(NOW.plusSeconds(30L));
      fixture.provider.awaitStarted();
      fixture.provider.fail(new ProviderException(ProviderErrorKind.TRANSIENT, "failure"));
      assertEquals(1, fixture.listener.errorCount.get());
    }

    try (Fixture fixture = new Fixture()) {
      fixture.listener.throwOnError = true;
      fixture.execute(NOW.plusSeconds(30L));
      fixture.provider.awaitStarted();
      fixture.provider.handler.get().onEvent(null, fixture.provider.stream);
      assertEquals(1, fixture.listener.errorCount.get());
    }
  }

  @Test
  void cancellationAfterProviderCreationPreventsTransportStart() throws Exception {
    CountDownLatch openerEntered = new CountDownLatch(1);
    CountDownLatch releaseOpener = new CountDownLatch(1);
    AtomicBoolean streamCalled = new AtomicBoolean();
    ModelProvider provider =
        (request, handler) -> {
          streamCalled.set(true);
          return new TestStream();
        };
    try (Fixture fixture =
        new Fixture(
            ignored -> {
              openerEntered.countDown();
              await(releaseOpener, "Provider opener release");
              return provider;
            })) {
      ModelExecutionHandle handle = fixture.execute(NOW.plusSeconds(30L));
      await(openerEntered, "Provider opener entry");

      handle.cancel();
      releaseOpener.countDown();
      fixture.awaitIdle();

      assertFalse(streamCalled.get());
    }
  }

  @Test
  void rejectsAStreamReturnedAfterCallbackBoundADifferentHandle() {
    TestStream callbackStream = new TestStream();
    TestStream returnedStream = new TestStream();
    ModelProvider provider =
        (request, handler) -> {
          handler.onEvent(new ProviderStreamEvent.TextDelta("partial"), callbackStream);
          return returnedStream;
        };
    try (Fixture fixture = new Fixture(ignored -> provider)) {
      fixture.execute(NOW.plusSeconds(30L));
      fixture.listener.awaitError();

      assertEquals(ProviderErrorKind.INVALID_REQUEST, fixture.listener.error.get().kind());
    }
  }

  @Test
  void cancelBeforeQueuedTaskPreventsProviderResolutionAndStart() {
    PausedExecutor executor = new PausedExecutor();
    try (Fixture fixture = new Fixture(new ControlledProvider(), executor)) {
      ModelExecutionHandle handle = fixture.execute(NOW.plusSeconds(30L));

      handle.cancel();
      executor.runAll();

      assertTrue(handle.isCancelled());
      assertEquals(0, fixture.openCount.get());
      assertFalse(fixture.provider.started.get());
    }
  }

  @Test
  void cancelBeforeStreamBindCancelsTheDelayedStreamExactlyOnce() {
    CountDownLatch release = new CountDownLatch(1);
    ControlledProvider provider = new ControlledProvider();
    provider.blockUntil(release);
    try (Fixture fixture = new Fixture(provider)) {
      ModelExecutionHandle handle = fixture.execute(NOW.plusSeconds(30L));
      provider.awaitStarted();

      handle.cancel();
      release.countDown();
      provider.stream.awaitCancelled();
      handle.cancel();

      assertTrue(handle.isCancelled());
      assertEquals(1, provider.stream.cancelCount.get());
    }
  }

  @Test
  void cancelAfterCallbackBindIsImmediateAndIdempotent() {
    try (Fixture fixture = new Fixture()) {
      ModelExecutionHandle handle = fixture.execute(NOW.plusSeconds(30L));
      fixture.provider.awaitStarted();
      fixture.provider.emit(new ProviderStreamEvent.TextDelta("bind"));

      handle.cancel();
      handle.cancel();

      assertTrue(handle.isCancelled());
      assertEquals(1, fixture.provider.stream.cancelCount.get());
    }
  }

  @Test
  void reflectsUnderlyingCancellationWithoutChangingLocalState() {
    try (Fixture fixture = new Fixture()) {
      ModelExecutionHandle handle = fixture.execute(NOW.plusSeconds(30L));
      fixture.provider.awaitStarted();
      fixture.provider.emit(new ProviderStreamEvent.TextDelta("bind"));

      fixture.provider.stream.externallyCancelled.set(true);

      assertTrue(handle.isCancelled());
      assertEquals(0, fixture.provider.stream.cancelCount.get());
    }
  }

  @Test
  void mapsExecutorSubmissionFailuresToTransientProviderFailures() {
    try (Fixture fixture = new Fixture(new ControlledProvider(), new RejectingExecutor())) {
      ProviderException rejected =
          assertThrows(ProviderException.class, () -> fixture.execute(NOW.plusSeconds(30L)));

      assertEquals(ProviderErrorKind.TRANSIENT, rejected.kind());
      assertTrue(rejected.getMessage().contains("rejected"));
    }

    try (Fixture fixture = new Fixture(new ControlledProvider(), new BrokenExecutor())) {
      ProviderException broken =
          assertThrows(ProviderException.class, () -> fixture.execute(NOW.plusSeconds(30L)));

      assertEquals(ProviderErrorKind.TRANSIENT, broken.kind());
      assertTrue(broken.getMessage().contains("cannot submit"));
    }
  }

  @Test
  void productionExecutorUsesVirtualThreads() throws Exception {
    ModelExecutionConfiguration configuration = new ModelExecutionConfiguration();
    assertEquals(ZoneOffset.UTC, configuration.modelExecutionClock().getZone());
    try (ExecutorService executor = configuration.modelExecutionExecutor()) {
      Future<Boolean> virtual = executor.submit(() -> Thread.currentThread().isVirtual());
      assertTrue(virtual.get(2L, TimeUnit.SECONDS));
    }
  }

  private static ProviderRequest frozenRequest() {
    ModelVariant variant =
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null);
    ModelDescriptor descriptor =
        new ModelDescriptor(
            22L,
            11L,
            ProviderType.OPENAI,
            "frozen-model",
            "Frozen model",
            4_096L,
            1_024L,
            EnumSet.of(ModelInputModality.TEXT),
            true,
            false,
            List.of(variant),
            new ModelPricing(
                "USD",
                "batch",
                "priority",
                BigDecimal.ONE,
                "v1",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO),
            PromptCachePolicy.disabled());
    return new ProviderRequest(
        descriptor, variant, List.of(), List.of(), ProviderCacheControl.none());
  }

  private static ProviderResponse response() {
    ModelUsage usage = new ModelUsage(1L, 1L, 0L, 0L, 0L, 0L, 2L);
    ModelCost cost =
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO);
    return new ProviderResponse(
        "", "", List.of(), ProviderStopReason.COMPLETED, usage, cost, null, null, "{}");
  }

  private static void await(CountDownLatch latch, String description) {
    try {
      assertTrue(latch.await(2L, TimeUnit.SECONDS), description);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting for " + description, failure);
    }
  }

  private static final class Fixture implements AutoCloseable {

    private final ControlledProvider provider;
    private final ExecutorService executor;
    private final AtomicReference<ModelCallTimeoutPolicy> openedPolicy = new AtomicReference<>();
    private final AtomicInteger openCount = new AtomicInteger();
    private final RecordingListener listener = new RecordingListener();
    private final ProviderCallExecutor subject;

    private Fixture() {
      this(new ControlledProvider());
    }

    private Fixture(ControlledProvider provider) {
      this(provider, Executors.newSingleThreadExecutor());
    }

    private Fixture(ControlledProvider provider, ExecutorService executor) {
      this(provider, executor, ignored -> provider);
    }

    private Fixture(Function<ModelCallTimeoutPolicy, ModelProvider> opener) {
      this(null, Executors.newSingleThreadExecutor(), opener);
    }

    private Fixture(
        ControlledProvider provider,
        ExecutorService executor,
        Function<ModelCallTimeoutPolicy, ModelProvider> opener) {
      this.provider = provider;
      this.executor = executor;
      ProviderResolutionService.ResolvedExecution resolved =
          new ProviderResolutionService.ResolvedExecution(
              CONFIGURED_POLICY,
              policy -> {
                openedPolicy.set(policy);
                openCount.incrementAndGet();
                return opener.apply(policy);
              });
      subject = new ProviderCallExecutor(resolved, executor, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ModelExecutionHandle execute(Instant deadlineAt) {
      return subject.execute(
          new ModelExecutionRequest(1L, 1, FROZEN_REQUEST, deadlineAt), listener);
    }

    private void awaitIdle() throws Exception {
      executor.submit(() -> {}).get(2L, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
      executor.shutdownNow();
    }
  }

  private static final class RecordingListener implements ModelExecutionListener {

    private final List<ProviderStreamEvent> deltas = new CopyOnWriteArrayList<>();
    private final AtomicReference<ProviderResponse> response = new AtomicReference<>();
    private final AtomicReference<ProviderException> error = new AtomicReference<>();
    private final AtomicInteger completeCount = new AtomicInteger();
    private final AtomicInteger errorCount = new AtomicInteger();
    private final CountDownLatch errorReceived = new CountDownLatch(1);
    private boolean throwOnDelta;
    private boolean throwOnComplete;
    private boolean throwOnError;

    @Override
    public void onDelta(ProviderStreamEvent delta) {
      if (throwOnDelta) {
        throw new IllegalStateException("delta projection failed");
      }
      deltas.add(delta);
    }

    @Override
    public void onComplete(ProviderResponse completed) {
      completeCount.incrementAndGet();
      response.set(completed);
      if (throwOnComplete) {
        throw new IllegalStateException("completion persistence failed");
      }
    }

    @Override
    public void onError(ProviderException failure) {
      errorCount.incrementAndGet();
      error.set(failure);
      errorReceived.countDown();
      if (throwOnError) {
        throw new IllegalStateException("failure persistence failed");
      }
    }

    private void awaitError() {
      await(errorReceived, "Provider error callback");
    }
  }

  private static final class ControlledProvider implements ModelProvider {

    private final ProviderResponse response = response();
    private final TestStream stream = new TestStream();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicReference<ProviderRequest> request = new AtomicReference<>();
    private final AtomicReference<ProviderStreamHandler> handler = new AtomicReference<>();
    private final CountDownLatch streamStarted = new CountDownLatch(1);
    private final CountDownLatch streamReturned = new CountDownLatch(1);
    private CountDownLatch release;
    private RuntimeException startFailure;
    private boolean returnNullStream;

    @Override
    public ProviderStream stream(
        ProviderRequest frozenRequest, ProviderStreamHandler streamHandler) {
      request.set(frozenRequest);
      handler.set(streamHandler);
      started.set(true);
      streamStarted.countDown();
      if (release != null) {
        try {
          if (!release.await(2L, TimeUnit.SECONDS)) {
            throw new IllegalStateException("timed out waiting for blocked Provider start release");
          }
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new ProviderException(
              ProviderErrorKind.CANCELLED, "Provider start was interrupted", failure);
        }
      }
      if (startFailure != null) {
        throw startFailure;
      }
      streamReturned.countDown();
      return returnNullStream ? null : stream;
    }

    private void blockUntil(CountDownLatch blockRelease) {
      release = blockRelease;
    }

    private void awaitStarted() {
      await(streamStarted, "Provider stream start");
    }

    private void awaitReturned() {
      await(streamReturned, "Provider stream return");
    }

    private void emit(ProviderStreamEvent event) {
      handler.get().onEvent(event, stream);
    }

    private void complete() {
      handler.get().onComplete(response, stream);
    }

    private void fail(ProviderException failure) {
      handler.get().onError(failure, stream);
    }
  }

  private static final class TestStream implements ProviderStream {

    private final AtomicInteger cancelCount = new AtomicInteger();
    private final AtomicBoolean externallyCancelled = new AtomicBoolean();
    private final CountDownLatch cancelled = new CountDownLatch(1);

    @Override
    public void cancel() {
      cancelCount.incrementAndGet();
      externallyCancelled.set(true);
      cancelled.countDown();
    }

    @Override
    public boolean isCancelled() {
      return externallyCancelled.get();
    }

    private void awaitCancelled() {
      await(cancelled, "Provider stream cancellation");
    }
  }

  private abstract static class StubExecutor extends AbstractExecutorService {

    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }
  }

  private static final class PausedExecutor extends StubExecutor {

    private final List<Runnable> tasks = new CopyOnWriteArrayList<>();

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    private void runAll() {
      tasks.forEach(Runnable::run);
      tasks.clear();
    }
  }

  private static final class RejectingExecutor extends StubExecutor {

    @Override
    public void execute(Runnable command) {
      throw new RejectedExecutionException("saturated");
    }
  }

  private static final class BrokenExecutor extends StubExecutor {

    @Override
    public void execute(Runnable command) {
      throw new IllegalStateException("executor broken");
    }
  }
}
