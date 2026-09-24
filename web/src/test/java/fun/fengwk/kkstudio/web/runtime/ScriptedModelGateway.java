package fun.fengwk.kkstudio.web.runtime;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.port.ModelGateway;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Issue 验收链集成测试使用的受控 {@link ModelGateway}：只把脚本化响应交给真实 Runtime，不发起任何 Provider 网络调用。
 *
 * <p>测试意图：验收链必须经过真实 HarnessRuntime（Model/Tool Processor、durable 历史、work 调度与恢复），但模型是非确定性的
 * 外部付费依赖；本类让测试完全掌控每个 turn 的产出，从而把 Runtime 行为与模型输出解耦，并保证可重复执行。
 *
 * <p>角色与 Run 身份的判定不依赖模型自报，只读取 Runtime 冻结的事实：
 *
 * <ul>
 *   <li>Reviewer Branch 的模型可见工具面包含 {@code issue_review}，Executor Branch 不包含；
 *   <li>当前 Run 从 Project 角色上下文投影（系统指令的一部分）中的 {@code run_id} 提取。
 * </ul>
 *
 * <p>同一 Run 的重复 turn（例如 ToolResult 回填后的 continuation）复用同一脚本决定，因此额外的 turn 不会错位后续 Run 的决定。
 */
public final class ScriptedModelGateway implements ModelGateway {

  /** 只有 REVIEWER 角色拥有的模型可见工具名：用它区分 Reviewer Branch，而非模型自报身份。 */
  static final String REVIEW_TOOL_NAME = "issue_review";

  private static final String APPROVE = "APPROVE";
  private static final String REQUEST_CHANGES = "REQUEST_CHANGES";

  /** 角色上下文投影中的当前 Run：Run 身份只来自系统指令，不来自模型自报。 */
  private static final Pattern RUN_ID_PATTERN = Pattern.compile("- run_id: ([0-9a-fA-F-]{36})");

  private final List<ProviderRequest> requests = new CopyOnWriteArrayList<>();
  private final Map<UUID, ReviewScript> reviewByRunId = new ConcurrentHashMap<>();
  private final Map<UUID, Integer> executorAttemptByRunId = new ConcurrentHashMap<>();

  private final AtomicInteger scriptedReviewRuns = new AtomicInteger();
  private final AtomicInteger executorAttempts = new AtomicInteger();
  private final AtomicBoolean reviewScriptExhausted = new AtomicBoolean();

  private volatile List<ReviewScript> reviewScript = List.of();

  public ScriptedModelGateway(List<ReviewScript> reviewScript) {
    reset(reviewScript);
  }

  /** 每个测试前重置脚本与观测状态：Context 复用同一个 Gateway 单例。 */
  public void reset(List<ReviewScript> reviewScript) {
    this.reviewScript = List.copyOf(Objects.requireNonNull(reviewScript, "reviewScript"));
    reviewByRunId.clear();
    executorAttemptByRunId.clear();
    scriptedReviewRuns.set(0);
    executorAttempts.set(0);
    reviewScriptExhausted.set(false);
    requests.clear();
  }

  @Override
  public StartResult start(Execution execution, Listener listener) {
    Objects.requireNonNull(execution, "execution");
    Objects.requireNonNull(listener, "listener");
    ProviderRequest request = execution.request();
    requests.add(request);
    return new Started(
        new Handle() {
          @Override
          public void cancel() {
            // 脚本化响应是同步内存交付：没有需要取消的外部执行。
          }

          @Override
          public void activate() {
            // 两阶段激活契约允许 Gateway 在 activate 期间同步回调；Runtime 会缓冲并在打开回调门控后按序重放。
            listener.onSucceeded(respond(request));
          }
        });
  }

  /** 已交付的 Provider 请求数（含 ToolResult continuation turn），供测试观测真实 Runtime 的调用次数。 */
  public int requestCount() {
    return requests.size();
  }

  /** 脚本决定是否已被用尽：用尽意味着出现了预期之外的 Reviewer Run。 */
  public boolean reviewScriptExhausted() {
    return reviewScriptExhausted.get();
  }

  private ProviderResponse respond(ProviderRequest request) {
    UUID runId = currentRunId(request);
    boolean reviewer = isReviewer(request);
    if (reviewer && runId != null && !endsWithToolResult(request)) {
      ReviewScript script = scriptForRun(runId);
      if (script != null) {
        return reviewResponse(script);
      }
    }
    return textResponse(summary(request, reviewer, runId));
  }

  private ProviderResponse reviewResponse(ReviewScript script) {
    String argumentsJson =
        "{\"decision\":\"" + script.decision() + "\",\"reason\":\"" + script.reason() + "\"}";
    return new ProviderResponse(
        "",
        null,
        List.of(new ProviderToolCall("call-" + script.hashCode(), REVIEW_TOOL_NAME, argumentsJson)),
        GenerationStopReason.COMPLETE,
        usage(),
        cost(),
        null,
        null,
        null);
  }

  private ProviderResponse textResponse(String text) {
    return new ProviderResponse(
        text, null, List.of(), GenerationStopReason.COMPLETE, usage(), cost(), null, null, null);
  }

  /**
   * 同一 Run 的决定必须稳定：一个 Run 可能因 ToolResult continuation 或人工/系统输入产生多个 turn，重复 turn 若领取下一个脚本决定 会让后续 Run
   * 的决定整体错位。
   */
  private ReviewScript scriptForRun(UUID runId) {
    ReviewScript existing = reviewByRunId.get(runId);
    if (existing != null) {
      return existing;
    }
    synchronized (reviewByRunId) {
      existing = reviewByRunId.get(runId);
      if (existing != null) {
        return existing;
      }
      int index = scriptedReviewRuns.getAndIncrement();
      if (index >= reviewScript.size()) {
        // 用尽脚本说明出现了预期之外的 Reviewer Run：返回纯文本让该 Run 收敛为失败，测试以有界超时给出明确结论。
        reviewScriptExhausted.set(true);
        return null;
      }
      ReviewScript script = reviewScript.get(index);
      reviewByRunId.put(runId, script);
      return script;
    }
  }

  private String summary(ProviderRequest request, boolean reviewer, UUID runId) {
    if (reviewer) {
      return "Review decision submitted.";
    }
    if (runId == null) {
      return "Executor turn completed without a bound run.";
    }
    int attempt =
        executorAttemptByRunId.computeIfAbsent(
            runId, ignored -> executorAttempts.incrementAndGet());
    return "Executor attempt " + attempt + " completed and verified.";
  }

  private static boolean isReviewer(ProviderRequest request) {
    return request.tools().stream().anyMatch(tool -> REVIEW_TOOL_NAME.equals(tool.name()));
  }

  /** ToolResult 回填后的 continuation turn：最后一条消息是 TOOL 结果，不再产生新的工具调用。 */
  private static boolean endsWithToolResult(ProviderRequest request) {
    List<ProviderMessage> messages = request.messages();
    return !messages.isEmpty()
        && messages.get(messages.size() - 1).role() == ProviderMessageRole.TOOL;
  }

  private static UUID currentRunId(ProviderRequest request) {
    Matcher matcher = RUN_ID_PATTERN.matcher(request.systemInstruction());
    if (!matcher.find()) {
      return null;
    }
    try {
      return UUID.fromString(matcher.group(1));
    } catch (IllegalArgumentException error) {
      return null;
    }
  }

  private static ModelUsage usage() {
    return new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L);
  }

  private static ModelCost cost() {
    return new ModelCost(
        "USD",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }

  /** 一次 Reviewer Run 的脚本化决定。 */
  public record ReviewScript(String decision, String reason) {

    public ReviewScript {
      if (!APPROVE.equals(decision) && !REQUEST_CHANGES.equals(decision)) {
        throw new IllegalArgumentException("decision must be APPROVE or REQUEST_CHANGES");
      }
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("reason must not be blank");
      }
      if (reason.indexOf('"') >= 0 || reason.indexOf('\\') >= 0) {
        throw new IllegalArgumentException("reason must not contain quotes or backslashes");
      }
    }

    public static ReviewScript approve(String reason) {
      return new ReviewScript(APPROVE, reason);
    }

    public static ReviewScript requestChanges(String reason) {
      return new ReviewScript(REQUEST_CHANGES, reason);
    }
  }
}
