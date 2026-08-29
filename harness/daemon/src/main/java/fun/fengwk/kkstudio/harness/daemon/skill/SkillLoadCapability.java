package fun.fengwk.kkstudio.harness.daemon.skill;

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

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** 根据名称加载 SKILL.md 指令正文的 Environment capability。 */
public final class SkillLoadCapability implements EnvironmentCapability {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
                EnvironmentCapabilityResult result = run(request);
                execution.complete(result);
              } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                execution.complete(
                    EnvironmentCapabilityResult.error(request.call().id(), "Operation cancelled"));
              } catch (Exception error) {
                execution.complete(
                    EnvironmentCapabilityResult.error(request.call().id(), error.getMessage()));
              }
            });
    return execution;
  }

  private EnvironmentCapabilityResult run(EnvironmentCapabilityExecutionRequest request)
      throws Exception {
    JsonNode args = OBJECT_MAPPER.readTree(request.call().argumentsJson());
    JsonNode nameNode = args.get("name");
    if (nameNode == null || !nameNode.isTextual() || nameNode.textValue().isBlank()) {
      return EnvironmentCapabilityResult.error(
          request.call().id(), "name is required and must be a non-blank string");
    }
    String name = nameNode.textValue();
    Optional<String> body = registry.loadBody(name);
    if (body.isEmpty()) {
      return EnvironmentCapabilityResult.error(request.call().id(), "unknown skill: " + name);
    }
    return EnvironmentCapabilityResult.text(request.call().id(), body.get());
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
