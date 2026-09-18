package fun.fengwk.kkstudio.harness.builtin.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 由当前 Thread Agent 选中、用于加载完整 SKILL.md 正文的 internal Tool。
 *
 * <p>校验冻结的已选 skill 与其 source Environment 后，直接经 {@link BoundEnvironment#execute} 以冻结的 {@code
 * sourceId/name/revision} 调用 {@code skill.load} 能力；不存在 skill body 专用的加载器链。{@code skill.load} 不要求
 * workdir：skill 按身份/来源定位，不依赖会话 cwd。
 */
public final class LoadSkillTool implements Tool {

  public static final String NAME = "load_skill";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final EnvironmentCapabilityDescriptor CAPABILITY =
      EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.SKILL_LOAD);
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          SkillToolPrompts.load("load_skill.md"),
          NAME,
          SkillToolPrompts.schema("load_skill.schema.json"),
          ToolSideEffect.READ_ONLY,
          Duration.ofMinutes(1));

  /** skill.load capability 的执行请求描述符：与 catalog 的能力 descriptor 匹配，仅用于经 BoundEnvironment 执行。 */
  private static final ToolDescriptor SKILL_LOAD_TOOL_DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          SkillToolPrompts.load("load_skill.md"),
          NAME,
          CAPABILITY.inputSchema(),
          ToolSideEffect.READ_ONLY,
          CAPABILITY.timeout());

  private final ThreadSelectedSkillLookup skillLookup;
  private final Supplier<Duration> loadTimeout;

  /** 无默认超时：加载超时必须由生产装配从 SystemSettings 快照显式传入（test 基座同样显式传 fixture 值）。 */
  public LoadSkillTool(ThreadSelectedSkillLookup skillLookup, Duration loadTimeout) {
    this(skillLookup, constantTimeout(loadTimeout));
  }

  public LoadSkillTool(ThreadSelectedSkillLookup skillLookup, Supplier<Duration> loadTimeout) {
    this.skillLookup = Objects.requireNonNull(skillLookup, "skillLookup");
    this.loadTimeout = Objects.requireNonNull(loadTimeout, "loadTimeout");
  }

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public ToolRequirements requirements() {
    return ToolRequirements.environment();
  }

  @Override
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
    String callId = request.call().id();
    try {
      ToolExecutionContext context = request.context();
      if (context == null) {
        throw new IllegalArgumentException("load_skill requires a durable execution context");
      }
      Optional<BoundEnvironment> environment = context.environment();
      if (environment.isEmpty()) {
        throw new IllegalArgumentException("No environment bound in execution context");
      }
      BoundEnvironment boundEnvironment = environment.get();
      String skillName = parseName(request.call().argumentsJson());
      Optional<SelectedSkill> selected =
          skillLookup.findSelected(context.invocationId(), context.threadId(), skillName);
      if (selected.isEmpty()) {
        return complete(listener, error(callId, "unknown or unselected skill: " + skillName));
      }
      SelectedSkill skill = selected.get();
      if (!Objects.equals(skill.sourceEnvironmentId(), boundEnvironment.environmentId())) {
        return complete(
            listener,
            error(
                callId,
                "skill source environment mismatch: expected "
                    + skill.sourceEnvironmentId()
                    + " but got "
                    + boundEnvironment.environmentId()));
      }
      return boundEnvironment.execute(
          CAPABILITY, skillLoadRequest(request, skill, loadTimeout()), listener);
    } catch (RuntimeException error) {
      return complete(listener, error(callId, message(error)));
    }
  }

  /**
   * skill.load 的 arguments 是冻结的全部身份字段（sourceId/name/revision），不含 workdir：内部能力必须精确匹配选择当时的版本，
   * 绝不允许按名称回退到同名新版本。arguments 由 Jackson 构造，避免手写转义引入非法 JSON。
   */
  private static ToolExecutionRequest skillLoadRequest(
      ToolExecutionRequest request, SelectedSkill skill, Duration timeout) {
    ObjectNode arguments = OBJECT_MAPPER.createObjectNode();
    arguments.put("sourceId", skill.sourceId().toString());
    arguments.put("name", skill.name());
    arguments.put("revision", skill.contentRevision());
    return new ToolExecutionRequest(
        SKILL_LOAD_TOOL_DESCRIPTOR,
        new ToolCall(request.call().id(), NAME, arguments.toString()),
        timeout,
        request.context());
  }

  private static String parseName(String argumentsJson) {
    try {
      JsonNode root = OBJECT_MAPPER.readTree(argumentsJson == null ? "{}" : argumentsJson);
      if (root == null || !root.isObject()) {
        throw new IllegalArgumentException("arguments must be a JSON object");
      }
      Iterator<String> names = root.fieldNames();
      while (names.hasNext()) {
        String field = names.next();
        if (!"name".equals(field)) {
          throw new IllegalArgumentException("unknown argument: " + field);
        }
      }
      JsonNode value = root.get("name");
      if (value == null || value.isNull() || !value.isTextual()) {
        throw new IllegalArgumentException("name is required and must be a string");
      }
      String text = value.textValue().trim();
      if (text.isEmpty()) {
        throw new IllegalArgumentException("name must not be blank");
      }
      return text;
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("arguments must be valid JSON", error);
    }
  }

  private static ToolExecutionHandle complete(ToolExecutionListener listener, ToolResult result) {
    listener.onComplete(result);
    return new CompletedHandle();
  }

  private static ToolResult error(String callId, String message) {
    String detail = message == null || message.isBlank() ? "tool execution failed" : message;
    return new ToolResult(callId, List.of(new TextResultContent(detail)), true, "{}");
  }

  private Duration loadTimeout() {
    Duration timeout = loadTimeout.get();
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalStateException("loadTimeout must be positive");
    }
    return timeout;
  }

  private static Supplier<Duration> constantTimeout(Duration loadTimeout) {
    Objects.requireNonNull(loadTimeout, "loadTimeout");
    if (loadTimeout.isZero() || loadTimeout.isNegative()) {
      throw new IllegalArgumentException("loadTimeout must be positive");
    }
    return () -> loadTimeout;
  }

  private static String message(Throwable error) {
    String detail = error.getMessage();
    return detail == null || detail.isBlank() ? error.getClass().getSimpleName() : detail;
  }

  /** 同步失败/拒绝时已通知 listener 的空 handle；取消是无意义操作。 */
  private static final class CompletedHandle implements ToolExecutionHandle {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    @Override
    public void cancel() {
      cancelled.set(true);
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }
  }
}
