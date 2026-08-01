package fun.fengwk.kkstudio.harness.runtime.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.runtime.goal.GoalToolPrompts;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PLATFORM tool that loads full SKILL.md text for a skill selected by the current Thread Agent.
 *
 * <p>Resolves only selected skills; source Environment comes from the binding persisted with the
 * invocation (platform-first). Does not expose local paths.
 */
public final class LoadSkillTool implements Tool {
  public static final String NAME = "load_skill";
  public static final String VERSION = "1";
  public static final Duration DEFAULT_LOAD_TIMEOUT = Duration.ofSeconds(30);

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          VERSION,
          GoalToolPrompts.load("load_skill.md"),
          NAME,
          new ToolParamsSchema(
              "Load a selected skill body by exact short name.",
              Map.of("name", new ToolStringSchema("Exact short skill name from available_skills.")),
              Set.of("name"),
              false),
          ToolSideEffect.READ_ONLY,
          Duration.ofMinutes(1));

  private final ThreadSelectedSkillLookup skillLookup;
  private final SkillBodyLoader skillBodyLoader;
  private final Duration loadTimeout;

  public LoadSkillTool(ThreadSelectedSkillLookup skillLookup, SkillBodyLoader skillBodyLoader) {
    this(skillLookup, skillBodyLoader, DEFAULT_LOAD_TIMEOUT);
  }

  public LoadSkillTool(
      ThreadSelectedSkillLookup skillLookup,
      SkillBodyLoader skillBodyLoader,
      Duration loadTimeout) {
    this.skillLookup = Objects.requireNonNull(skillLookup, "skillLookup");
    this.skillBodyLoader = Objects.requireNonNull(skillBodyLoader, "skillBodyLoader");
    this.loadTimeout = Objects.requireNonNull(loadTimeout, "loadTimeout");
    if (loadTimeout.isZero() || loadTimeout.isNegative()) {
      throw new IllegalArgumentException("loadTimeout must be positive");
    }
  }

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
    String callId = request.call().id();
    Handle handle = new Handle(listener, callId);
    try {
      if (request.context() == null) {
        throw new IllegalArgumentException("load_skill requires a durable execution context");
      }
      String skillName = parseName(request.call().argumentsJson());
      Optional<SkillBinding> selected =
          skillLookup.findSelected(
              request.context().invocationId(), request.context().threadId(), skillName);
      if (selected.isEmpty()) {
        complete(handle, error(callId, "unknown or unselected skill: " + skillName));
        return handle;
      }
      SkillBinding skill = selected.get();
      CompletableFuture<SkillBodyLoader.SkillBodyLoadResult> future =
          skillBodyLoader.load(skill.sourceEnvironment(), skill.name(), loadTimeout);
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
                      callId, List.of(new TextToolContent(loaded.content())), false, "{}", false));
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
    return new ToolResult(callId, List.of(new TextToolContent(detail)), true, "{}", false);
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
