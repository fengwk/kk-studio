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
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfig;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfigCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshot;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshotCodec;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Skill 来源管理能力（refresh/install/update）。
 *
 * <p>三个操作复用完全相同的 arguments 形状（冻结来源配置）与结果形状（来源快照），只有执行语义不同；它们共享 INVOKE/CANCEL/终态通道，但不注册为模型 Tool，也不接受
 * workdir。取消是<strong>调用级</strong>的：只中断本次执行自己的 Future，因此并发执行的其它管理调用不受影响；已发布快照始终保持不变。
 */
public final class DaemonSkillSourceCapability implements EnvironmentCapability {

  /** 管理操作类型；每个类型对应一个固定 capability 身份。 */
  public enum Operation {
    REFRESH(EnvironmentCapabilityIds.SKILL_SOURCE_REFRESH),
    INSTALL(EnvironmentCapabilityIds.SKILL_SOURCE_INSTALL),
    UPDATE(EnvironmentCapabilityIds.SKILL_SOURCE_UPDATE);

    private final EnvironmentCapabilityId capabilityId;

    Operation(EnvironmentCapabilityId capabilityId) {
      this.capabilityId = capabilityId;
    }

    EnvironmentCapabilityId capabilityId() {
      return capabilityId;
    }
  }

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final DaemonSkillSourceConfigCodec CONFIG_CODEC =
      new DaemonSkillSourceConfigCodec();
  private static final DaemonSkillSourceSnapshotCodec SNAPSHOT_CODEC =
      new DaemonSkillSourceSnapshotCodec();

  /** 非法 arguments 的固定结构性文本：不回显字段值，也不携带任何本地事实。 */
  static final String INVALID_REQUEST_MESSAGE = "invalid skill source request";

  /** 操作失败的固定结构性文本：绝不携带异常消息/类、本地路径、URL、ref、argv 或子进程输出。 */
  static final String OPERATION_FAILED_MESSAGE = "skill source operation failed";

  private final Operation operation;
  private final EnvironmentCapabilityId capabilityId;
  private final DaemonSkillRegistry registry;
  private final ExecutorService executor;
  private final EnvironmentCapabilityDescriptor descriptor;

  public DaemonSkillSourceCapability(
      Operation operation, DaemonSkillRegistry registry, ExecutorService executor) {
    this.operation = Objects.requireNonNull(operation, "operation");
    this.capabilityId = operation.capabilityId();
    this.registry = Objects.requireNonNull(registry, "registry");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.descriptor = EnvironmentCapabilityCatalog.require(capabilityId);
  }

  /** 执行一次来源操作：refresh 只做本地扫描，install/update 允许一次普通 Git 获取。 */
  private DaemonSkillSourceSnapshot apply(DaemonSkillSourceConfig config) {
    return switch (operation) {
      case REFRESH -> registry.refresh(config);
      case INSTALL -> registry.install(config);
      case UPDATE -> registry.update(config);
    };
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
                // 失败文本恒为常量：异常消息可能携带本地路径、URL/凭据或子进程输出。
                execution.complete(
                    EnvironmentCapabilityResult.error(
                        request.call().id(), OPERATION_FAILED_MESSAGE));
              }
            });
    return execution;
  }

  /** arguments 就是冻结的来源配置本身（与 catalog 声明的 arguments schema 完全一致），没有包装字段。 */
  private EnvironmentCapabilityResult run(EnvironmentCapabilityExecutionRequest request)
      throws JsonProcessingException {
    JsonNode args = OBJECT_MAPPER.readTree(request.call().argumentsJson());
    if (args == null || !args.isObject()) {
      return EnvironmentCapabilityResult.error(request.call().id(), INVALID_REQUEST_MESSAGE);
    }
    DaemonSkillSourceConfig config;
    try {
      config = CONFIG_CODEC.decode(args.toString());
    } catch (RuntimeException error) {
      return EnvironmentCapabilityResult.error(request.call().id(), INVALID_REQUEST_MESSAGE);
    }
    try {
      DaemonSkillSourceSnapshot snapshot = apply(config);
      return EnvironmentCapabilityResult.json(
          request.call().id(), SNAPSHOT_CODEC.encodeNode(snapshot).toString());
    } catch (DaemonSkillException error) {
      return EnvironmentCapabilityResult.error(request.call().id(), OPERATION_FAILED_MESSAGE);
    }
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

    /**
     * 取消本次执行：只取消自己的 Future 并要求中断正在运行的线程。
     *
     * <p>取消是调用级事实，绝不触碰 registry 或其它并发调用；git 子进程由该线程自己终止，staging 由失败路径清理。
     */
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
