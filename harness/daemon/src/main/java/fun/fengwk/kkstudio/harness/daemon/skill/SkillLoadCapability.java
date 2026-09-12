package fun.fengwk.kkstudio.harness.daemon.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResultCodes;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;

import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按冻结 {@code (sourceId, name, revision)} 精确加载 Skill 正文的 Environment capability。
 *
 * <p>参数必须包含全部三个身份字段；命中保留版本时返回 JSON {@code {"body","baseDirectory"}}，未保留该版本时返回带稳定 {@link
 * EnvironmentCapabilityResultCodes#RESOURCE_CHANGED} 码的错误结果，绝不返回当前新版本冒充旧版本。错误文本不包含本地路径或 正文内容。能力不接受
 * workdir：skill 按身份定位，与会话目录无关。
 */
public final class SkillLoadCapability implements EnvironmentCapability {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Set<String> ARGUMENT_FIELDS = Set.of("sourceId", "name", "revision");

  /** 非法 arguments 的固定结构性文本：不回显字段值，也不携带任何本地事实。 */
  static final String INVALID_REQUEST_MESSAGE = "invalid skill.load request";

  /** 加载失败的固定结构性文本：绝不携带异常消息/类、本地路径或正文。 */
  static final String LOAD_FAILED_MESSAGE = "skill.load failed";

  /** 请求的冻结 revision 已不再保留时的固定可读文本：正文绝不冒充旧版本。 */
  static final String RESOURCE_CHANGED_MESSAGE =
      "the requested skill revision is no longer available;"
          + " re-select the skill to obtain its current revision";

  private final DaemonSkillRegistry registry;
  private final ExecutorService executor;
  private final EnvironmentCapabilityDescriptor descriptor;

  public SkillLoadCapability(DaemonSkillRegistry registry, ExecutorService executor) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.descriptor = EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.SKILL_LOAD);
  }

  @Override
  public EnvironmentCapabilityDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public EnvironmentCapabilityExecutionHandle execute(
      EnvironmentCapabilityExecutionRequest request,
      EnvironmentCapabilityExecutionListener listener) {
    if (!descriptor.equals(request.descriptor())) {
      throw new IllegalArgumentException("request descriptor does not match capability descriptor");
    }
    Objects.requireNonNull(listener, "listener");
    Execution execution = new Execution(request.call().id(), listener);
    execution.worker =
        executor.submit(
            () -> {
              try {
                execution.complete(run(request));
              } catch (JsonProcessingException error) {
                execution.complete(
                    EnvironmentCapabilityResult.error(
                        request.call().id(), INVALID_REQUEST_MESSAGE));
              } catch (RuntimeException error) {
                // 失败文本恒为常量：异常消息可能携带本地路径或正文内容。
                execution.complete(
                    EnvironmentCapabilityResult.error(request.call().id(), LOAD_FAILED_MESSAGE));
              }
            });
    return execution;
  }

  private EnvironmentCapabilityResult run(EnvironmentCapabilityExecutionRequest request)
      throws JsonProcessingException {
    JsonNode args = OBJECT_MAPPER.readTree(request.call().argumentsJson());
    if (args == null || !args.isObject() || !hasExactArgumentFields(args)) {
      return EnvironmentCapabilityResult.error(request.call().id(), INVALID_REQUEST_MESSAGE);
    }
    UUID sourceId = sourceId(args);
    String name = text(args, "name");
    String revision = text(args, "revision");
    if (sourceId == null || name == null || revision == null) {
      return EnvironmentCapabilityResult.error(request.call().id(), INVALID_REQUEST_MESSAGE);
    }
    try {
      name = DaemonSkillDescriptor.canonicalName(name);
      revision = DaemonSkillDescriptor.contentRevision(revision);
    } catch (IllegalArgumentException error) {
      return EnvironmentCapabilityResult.error(request.call().id(), INVALID_REQUEST_MESSAGE);
    }
    Optional<DaemonSkillRegistry.LoadedSkill> loaded = registry.load(sourceId, name, revision);
    if (loaded.isEmpty()) {
      return EnvironmentCapabilityResult.codedError(
          request.call().id(),
          EnvironmentCapabilityResultCodes.RESOURCE_CHANGED,
          RESOURCE_CHANGED_MESSAGE);
    }
    String payload = SkillLoadPayload.encode(loaded.get().body(), loaded.get().baseDirectory());
    return EnvironmentCapabilityResult.json(request.call().id(), payload);
  }

  private static boolean hasExactArgumentFields(JsonNode args) {
    int count = 0;
    Iterator<String> fields = args.fieldNames();
    while (fields.hasNext()) {
      if (!ARGUMENT_FIELDS.contains(fields.next())) {
        return false;
      }
      count++;
    }
    return count == ARGUMENT_FIELDS.size();
  }

  private static UUID sourceId(JsonNode args) {
    String value = text(args, "sourceId");
    if (value == null) {
      return null;
    }
    try {
      UUID parsed = UUID.fromString(value);
      return parsed.toString().equals(value) ? parsed : null;
    } catch (IllegalArgumentException error) {
      return null;
    }
  }

  private static String text(JsonNode args, String field) {
    if (args == null || !args.isObject()) {
      return null;
    }
    JsonNode value = args.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      return null;
    }
    return value.textValue();
  }

  private static final class Execution implements EnvironmentCapabilityExecutionHandle {
    private final String callId;
    private final EnvironmentCapabilityExecutionListener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private volatile Future<?> worker;

    private Execution(String callId, EnvironmentCapabilityExecutionListener listener) {
      this.callId = callId;
      this.listener = listener;
    }

    @Override
    public void cancel() {
      if (cancelled.compareAndSet(false, true)) {
        Future<?> current = worker;
        if (current != null) {
          current.cancel(true);
        }
        complete(EnvironmentCapabilityResult.error(callId, "Operation cancelled"));
      }
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    void complete(EnvironmentCapabilityResult result) {
      if (terminal.compareAndSet(false, true)) {
        listener.onComplete(result);
      }
    }
  }
}
