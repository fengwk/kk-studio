package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.runtime.tool.AfterToolCallContext;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInterceptorChain;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.ResourceRef;
import fun.fengwk.kkstudio.harness.tool.ResourceToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Terminal result preparation and durable externalization for the Tool worker.
 *
 * <p>This component owns everything that turns a successful {@link Tool} invocation into a
 * persisted {@code SUCCEEDED} row, plus the parallel terminal paths for {@code FAILED}, {@code
 * CANCELLED}, and {@code UNKNOWN}. It is intentionally Tool-execution agnostic; {@link
 * ExecutionCallback} feeds it {@link ToolResult} instances and the {@link
 * ToolInvocationTransactions} port owns the durable mutation.
 *
 * <p>Preparation order for {@link #prepareTerminalResult}: after-tool-call interceptors first, then
 * {@link ResourceStore} externalization of oversized or binary content. Either step failing is
 * captured as a {@link TerminalResultPreparationException} so the caller can converge to a
 * deterministic {@code FAILED} row with the matching error kind and never run the persisted-success
 * path.
 */
final class TerminalCompleter {

  private final ToolInvocationTransactions transactions;
  private final ToolInterceptorChain interceptorChain;
  private final ResourceStore resourceStore;
  private final ToolWorkerConfig config;
  private final Clock clock;

  TerminalCompleter(
      ToolInvocationTransactions transactions,
      ToolInterceptorChain interceptorChain,
      ResourceStore resourceStore,
      ToolWorkerConfig config,
      Clock clock) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.interceptorChain = Objects.requireNonNull(interceptorChain, "interceptorChain");
    this.resourceStore = Objects.requireNonNull(resourceStore, "resourceStore");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Terminal {@code SUCCEEDED} write. Persists the prepared result through the transactions port.
   */
  boolean completeSuccess(
      ClaimedToolInvocation claimed,
      ToolBinding binding,
      ToolCall call,
      ToolResult result,
      Instant lastObservedActivityAt) {
    Instant now = clock.instant();
    ToolInvocationUpdateOutcome outcome;
    try {
      outcome =
          transactions.completeSuccess(
              claimed,
              () -> prepareTerminalResult(claimed, binding, call, result),
              lastObservedActivityAt,
              now);
    } catch (TerminalResultPreparationException error) {
      return completeFailure(
          claimed, new ToolInvocationError(error.kind, error.getMessage()), lastObservedActivityAt);
    } catch (RuntimeException error) {
      return completeFailure(
          claimed,
          new ToolInvocationError(
              "RESULT_PERSISTENCE_FAILED",
              failureMessage(error, "Tool result persistence failed.")),
          lastObservedActivityAt);
    }
    return outcome == ToolInvocationUpdateOutcome.APPLIED;
  }

  /** Terminal {@code FAILED} write. */
  boolean completeFailure(
      ClaimedToolInvocation claimed, ToolInvocationError error, Instant lastObservedActivityAt) {
    Instant now = clock.instant();
    return transactions.completeFailure(claimed, error, lastObservedActivityAt, now)
        == ToolInvocationUpdateOutcome.APPLIED;
  }

  /** Terminal {@code CANCELLED} write. */
  boolean completeCancelled(ClaimedToolInvocation claimed, Instant lastObservedActivityAt) {
    Instant now = clock.instant();
    return transactions.completeCancelled(claimed, lastObservedActivityAt, now)
        == ToolInvocationUpdateOutcome.APPLIED;
  }

  /** Terminal {@code UNKNOWN} write. Used for uncertain side-effect outcomes. */
  boolean completeUnknown(
      ClaimedToolInvocation claimed, ToolInvocationError error, Instant lastObservedActivityAt) {
    Instant now = clock.instant();
    return transactions.completeUnknown(claimed, error, lastObservedActivityAt, now)
        == ToolInvocationUpdateOutcome.APPLIED;
  }

  /**
   * Prepare the persisted {@link ToolResult}: after-tool-call interceptors first, then {@link
   * ResourceStore} externalization of any oversized or binary content. Either failure is reported
   * as a {@link TerminalResultPreparationException} so the caller can fail closed without writing a
   * partial terminal row.
   */
  ToolResult prepareTerminalResult(
      ClaimedToolInvocation claimed, ToolBinding binding, ToolCall call, ToolResult result) {
    ToolResult intercepted;
    try {
      intercepted =
          interceptorChain.after(
              new AfterToolCallContext(claimed.invocation().id(), binding, call, result));
    } catch (RuntimeException error) {
      throw new TerminalResultPreparationException(
          "AFTER_INTERCEPTOR_FAILED", afterInterceptorFailure(error), error);
    }
    try {
      return externalize(intercepted);
    } catch (RuntimeException error) {
      throw new TerminalResultPreparationException(
          "RESULT_PERSISTENCE_FAILED",
          failureMessage(error, "Tool result persistence failed."),
          error);
    }
  }

  private ToolResult externalize(ToolResult result) {
    List<ToolContent> contents = new ArrayList<>();
    for (ToolContent content : result.contents()) {
      if (content instanceof BinaryToolContent binary) {
        byte[] bytes = binary.content();
        ResourceRef resource = resourceStore.put(binary.mediaType(), null, bytes);
        contents.add(new TextToolContent(BINARY_RESOURCE_PREVIEW));
        contents.add(new ResourceToolContent(resource));
        continue;
      }
      if (content instanceof ResourceToolContent) {
        // 已有规范 Resource 引用直接透传，不做二次外部化。
        contents.add(content);
        continue;
      }
      byte[] bytes = bytes(content);
      if (bytes.length <= config.inlineResultBytes()) {
        contents.add(content);
        continue;
      }
      String mediaType = content instanceof JsonToolContent ? "application/json" : "text/plain";
      // The terminal transaction invokes this method only after validating and locking ownership.
      ResourceRef resource = resourceStore.put(mediaType, null, bytes);
      contents.add(new TextToolContent(preview(bytes)));
      contents.add(new ResourceToolContent(resource));
    }
    if (contents.isEmpty()) {
      contents.add(new TextToolContent(""));
    }
    return new ToolResult(
        result.toolCallId(), contents, result.error(), result.detailsJson(), false);
  }

  private static byte[] bytes(ToolContent content) {
    if (content instanceof TextToolContent text) {
      return text.text().getBytes(StandardCharsets.UTF_8);
    }
    if (content instanceof JsonToolContent json) {
      return json.json().getBytes(StandardCharsets.UTF_8);
    }
    return new byte[0];
  }

  /** Binary 输出外部化时的稳定文本标记；二进制字节不产生文本 preview。 */
  private static final String BINARY_RESOURCE_PREVIEW = "[binary output stored as resource]";

  private String preview(byte[] bytes) {
    if (bytes.length <= config.previewBytes()) {
      return new String(bytes, StandardCharsets.UTF_8);
    }
    String value = new String(bytes, StandardCharsets.UTF_8);
    StringBuilder prefix = new StringBuilder();
    int previewBytes = 0;
    for (int offset = 0; offset < value.length(); ) {
      int codePoint = value.codePointAt(offset);
      int codePointBytes =
          new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
      if (previewBytes + codePointBytes > config.previewBytes()) {
        break;
      }
      prefix.appendCodePoint(codePoint);
      previewBytes += codePointBytes;
      offset += Character.charCount(codePoint);
    }
    return prefix + "\n[full output stored as resource]";
  }

  private static String failureMessage(Throwable error, String fallback) {
    String message = error.getMessage();
    return message == null || message.isBlank() ? fallback : message;
  }

  private static String afterInterceptorFailure(RuntimeException error) {
    return failureMessage(error, "afterToolCall interceptor failed.");
  }

  /**
   * Marker exception thrown from {@link #prepareTerminalResult} when either preparation step fails.
   * The caller translates it into the corresponding terminal {@code FAILED} row.
   */
  private static final class TerminalResultPreparationException extends RuntimeException {
    private final String kind;

    private TerminalResultPreparationException(String kind, String message, Throwable cause) {
      super(message, cause);
      this.kind = kind;
    }
  }
}
