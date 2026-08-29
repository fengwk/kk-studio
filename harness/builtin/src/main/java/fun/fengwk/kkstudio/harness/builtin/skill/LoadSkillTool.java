package fun.fengwk.kkstudio.harness.builtin.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.builtin.BuiltinToolIds;
import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 由当前 Thread Agent 选中、用于加载完整 SKILL.md 正文的 internal Tool。
 *
 * <p>仅解析已选中的 skill；source Environment 来自 invocation 持久化的 binding（binding-first）。 不暴露本地路径。
 */
public final class LoadSkillTool implements Tool {
  public static final String NAME = "load_skill";
  public static final String VERSION = "1";
  public static final AgentToolId AGENT_TOOL_ID = BuiltinToolIds.LOAD_SKILL;

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          VERSION,
          SkillToolPrompts.load("load_skill.md"),
          NAME,
          SkillToolPrompts.schema("load_skill.schema.json"),
          ToolSideEffect.READ_ONLY,
          Duration.ofMinutes(1));

  private final ThreadSelectedSkillLookup skillLookup;
  private final SkillBodyLoader skillBodyLoader;
  private final Supplier<Duration> loadTimeout;

  /** 无默认超时：加载超时必须由生产装配从 SystemSettings 快照显式传入（test 基座同样显式传 fixture 值）。 */
  public LoadSkillTool(
      ThreadSelectedSkillLookup skillLookup,
      SkillBodyLoader skillBodyLoader,
      Duration loadTimeout) {
    this(skillLookup, skillBodyLoader, constantTimeout(loadTimeout));
  }

  public LoadSkillTool(
      ThreadSelectedSkillLookup skillLookup,
      SkillBodyLoader skillBodyLoader,
      Supplier<Duration> loadTimeout) {
    this.skillLookup = Objects.requireNonNull(skillLookup, "skillLookup");
    this.skillBodyLoader = Objects.requireNonNull(skillBodyLoader, "skillBodyLoader");
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
    Handle handle = new Handle(listener, callId);
    try {
      ToolExecutionContext context = request.context();
      if (context == null) {
        throw new IllegalArgumentException("load_skill requires a durable execution context");
      }
      Optional<BoundEnvironment> environment = context.environment();
      if (environment.isEmpty()) {
        throw new IllegalArgumentException("No environment bound in execution context");
      }
      BoundEnvironment boundEnv = environment.get();
      String skillName = parseName(request.call().argumentsJson());
      Optional<SelectedSkill> selected =
          skillLookup.findSelected(context.invocationId(), context.threadId(), skillName);
      if (selected.isEmpty()) {
        complete(handle, error(callId, "unknown or unselected skill: " + skillName));
        return handle;
      }
      SelectedSkill skill = selected.get();
      if (skill.sourceEnvironment() == null) {
        complete(handle, error(callId, "skill has no Environment body: " + skill.name()));
        return handle;
      }
      if (!Objects.equals(skill.sourceEnvironment(), boundEnv.binding())) {
        complete(
            handle,
            error(
                callId,
                "skill source environment mismatch: expected "
                    + skill.sourceEnvironment().environmentName().value()
                    + " but got "
                    + boundEnv.binding().environmentName().value()));
        return handle;
      }
      CompletableFuture<SkillBodyLoader.SkillBodyLoadResult> future =
          skillBodyLoader.load(skill.sourceEnvironment(), skill.name(), loadTimeout());
      handle.future.set(future);
      future.whenComplete(
          (result, error) -> {
            if (handle.cancelled.get() || handle.completed.get()) {
              return;
            }
            if (error != null) {
              complete(handle, error(callId, failureMessage(skill.name(), error)));
              return;
            }
            if (result instanceof SkillBodyLoader.SkillBodyLoadResult.Loaded loaded) {
              complete(
                  handle,
                  new ToolResult(
                      callId, List.of(new TextToolContent(loaded.content())), false, "{}"));
            } else if (result instanceof SkillBodyLoader.SkillBodyLoadResult.Failed failed) {
              complete(handle, error(callId, failed.message()));
            } else {
              complete(handle, error(callId, skill.name() + " load returned an empty result"));
            }
          });
    } catch (RuntimeException error) {
      complete(handle, error(callId, message(error)));
    }
    return handle;
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

  private static void complete(Handle handle, ToolResult result) {
    if (handle.completed.compareAndSet(false, true)) {
      handle.listener.onComplete(result);
    }
  }

  private static ToolResult error(String callId, String message) {
    String detail = message == null || message.isBlank() ? "tool execution failed" : message;
    return new ToolResult(callId, List.of(new TextToolContent(detail)), true, "{}");
  }

  private static String failureMessage(String skillName, Throwable error) {
    Throwable cause =
        error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    if (cause instanceof CancellationException) {
      return skillName + " load was cancelled";
    }
    String detail = cause.getMessage();
    if (detail == null || detail.isBlank()) {
      return skillName + " is offline; " + skillName + " is unavailable";
    }
    return detail;
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

  private static final class Handle implements ToolExecutionHandle {
    private final ToolExecutionListener listener;
    private final String callId;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<?>> future = new AtomicReference<>();

    private Handle(ToolExecutionListener listener, String callId) {
      this.listener = listener;
      this.callId = callId;
    }

    @Override
    public void cancel() {
      if (cancelled.compareAndSet(false, true)) {
        CompletableFuture<?> pending = future.get();
        if (pending != null) {
          pending.cancel(true);
        }
        if (completed.compareAndSet(false, true)) {
          listener.onComplete(error(callId, "Operation cancelled"));
        }
      }
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }
  }
}
