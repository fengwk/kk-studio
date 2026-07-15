package fun.fengwk.kkstudio.harness.runtime.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionPromptPreview;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 在任何 Tool 副作用前完成 binding、interceptor、schema 与 permission preparation。 */
public final class ToolPreparationService {
  private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

  private final ToolInvocationIdGenerator idGenerator;
  private final ToolInterceptorChain interceptorChain;
  private final ObjectMapper objectMapper;
  private final Duration defaultTimeout;

  public ToolPreparationService(
      ToolInvocationIdGenerator idGenerator,
      ToolInterceptorChain interceptorChain,
      ObjectMapper objectMapper) {
    this(idGenerator, interceptorChain, objectMapper, DEFAULT_TIMEOUT);
  }

  public ToolPreparationService(
      ToolInvocationIdGenerator idGenerator,
      ToolInterceptorChain interceptorChain,
      ObjectMapper objectMapper,
      Duration defaultTimeout) {
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    this.interceptorChain = Objects.requireNonNull(interceptorChain, "interceptorChain");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.defaultTimeout = requirePositive(defaultTimeout, "defaultTimeout");
  }

  public List<PreparedToolInvocation> prepare(
      List<ToolCall> toolCalls,
      List<ToolBinding> bindings,
      ToolSettings settings,
      boolean yoloEnabled,
      Path workdir,
      Path environmentRoot,
      Instant now) {
    toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
    now = Objects.requireNonNull(now, "now");
    if (toolCalls.isEmpty()) {
      throw new IllegalArgumentException("tool preparation requires at least one call");
    }
    if (!interceptorChain.hasPermissionBoundary()) {
      throw new IllegalStateException(
          "tool preparation requires exactly one PermissionBoundaryInterceptor");
    }
    Map<String, ToolBinding> byName = indexBindings(bindings);
    Set<String> toolCallIds = new HashSet<>();
    List<PreparedToolInvocation> prepared = new ArrayList<>(toolCalls.size());
    for (int ordinal = 0; ordinal < toolCalls.size(); ordinal++) {
      ToolCall original = toolCalls.get(ordinal);
      if (!toolCallIds.add(original.id())) {
        throw new IllegalArgumentException("duplicate toolCallId: " + original.id());
      }
      ToolBinding binding = byName.get(original.toolName());
      if (binding == null) {
        throw new IllegalArgumentException(
            "tool call has no frozen binding: " + original.toolName());
      }
      BeforeToolCallResult intercepted =
          interceptorChain.before(
              binding, original, settings, yoloEnabled, workdir, environmentRoot);
      PermissionAction action = Objects.requireNonNull(intercepted.permissionAction());
      PermissionPromptPreview promptPreview =
          Objects.requireNonNull(intercepted.permissionPromptPreview());
      ToolCall interceptedCall =
          new ToolCall(
              original.id(),
              intercepted.binding().descriptor().name(),
              intercepted.argumentsJson());
      ToolInvocationStatus status = initialStatus(action);
      String errorMessage = null;
      String resultJson = null;
      if (action == PermissionAction.DENY) {
        errorMessage = "Permission denied for " + interceptedCall.toolName() + ".";
        resultJson = deniedResult(interceptedCall.id(), errorMessage);
      }
      prepared.add(
          new PreparedToolInvocation(
              idGenerator.newInvocationId(),
              ordinal,
              intercepted.binding(),
              interceptedCall,
              action,
              status,
              deadlineAt(now, intercepted.binding()),
              promptPreview,
              resultJson,
              errorMessage));
    }
    return List.copyOf(prepared);
  }

  private Map<String, ToolBinding> indexBindings(List<ToolBinding> bindings) {
    Map<String, ToolBinding> result = new HashMap<>();
    for (ToolBinding binding : List.copyOf(Objects.requireNonNull(bindings, "bindings"))) {
      String name = binding.descriptor().name();
      if (result.put(name, binding) != null) {
        throw new IllegalArgumentException("duplicate tool binding: " + name);
      }
    }
    return result;
  }

  private ToolInvocationStatus initialStatus(PermissionAction action) {
    return switch (action) {
      case ALLOW -> ToolInvocationStatus.QUEUED;
      case ASK -> ToolInvocationStatus.WAITING_APPROVAL;
      case DENY -> ToolInvocationStatus.FAILED;
    };
  }

  private Instant deadlineAt(Instant now, ToolBinding binding) {
    try {
      return now.plus(effectiveTimeout(binding));
    } catch (DateTimeException | ArithmeticException error) {
      throw new IllegalArgumentException(
          "tool timeout exceeds the supported instant range: " + binding.descriptor().name(),
          error);
    }
  }

  private Duration effectiveTimeout(ToolBinding binding) {
    Duration timeout = binding.descriptor().timeout();
    return timeout.isZero() ? defaultTimeout : timeout;
  }

  private static Duration requirePositive(Duration value, String name) {
    value = Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  private String deniedResult(String toolCallId, String message) {
    ObjectNode result = objectMapper.createObjectNode();
    result.put("toolCallId", toolCallId);
    ArrayNode contents = result.putArray("contents");
    ObjectNode text = contents.addObject();
    text.put("type", "text");
    text.put("text", message);
    result.put("error", true);
    result.set("details", objectMapper.createObjectNode());
    try {
      return objectMapper.writeValueAsString(result);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("cannot encode denied tool result", error);
    }
  }
}
