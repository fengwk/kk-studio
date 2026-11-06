package fun.fengwk.kkstudio.platform.harness.task;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.hash.Sha256;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.HarnessConflictReason;
import fun.fengwk.kkstudio.harness.api.SpawnRequest;
import fun.fengwk.kkstudio.harness.api.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetExecutionCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.history.SubagentBinding;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.codec.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.contributor.builtin.subagent.SubagentConfig;
import fun.fengwk.kkstudio.harness.contributor.builtin.subagent.SubagentConfigProvider;
import fun.fengwk.kkstudio.harness.contributor.builtin.subagent.SubagentRunner;
import fun.fengwk.kkstudio.harness.contributor.builtin.subagent.SubagentTaskAcceptance;
import fun.fengwk.kkstudio.harness.contributor.builtin.subagent.SubagentTaskRequest;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.spi.AcceptancePreflight;
import fun.fengwk.kkstudio.platform.agenttask.AgentTaskRequest;
import fun.fengwk.kkstudio.platform.agenttask.AgentTaskService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 把 {@code task} 委派翻译为 Runtime 的原子「源命令 + join」接受。
 *
 * <p>本类只做两件事：用父调用的冻结快照校验委派权限（允许的 subagent、目标 settings），并把请求规范化成稳定的 join 身份（子 Thread UUID、请求
 * hash、命令幂等键）。深度/并发额度、父子归属复核、匹配与交付全部由 Runtime 在树锁内裁决，平台不持有第二套权威状态，也不写入自己的 task 表。
 *
 * <p>幂等由 join 的 {@code invocationId} 承担：入场与每次重试都先重读既有 join，命中且与本次请求一致时直接回放已接受的子身份，绝不重新构造命令
 * batch。这样并发重复调用（含目标 settings 期间变化）不会退化成「部分重放」错误，也不会因为重新物化默认 {@code maxTurns} 而改写原回执。
 *
 * <p>Runtime 的业务拒绝统一收敛为 {@link SubagentTaskRejectedException}（tool 得到明确失败而不是
 * NullPointerException）；Runtime 不可用时同样给出 明确错误。
 */
public class SubagentTaskRunner implements SubagentRunner {

  private static final String CHILD_SESSION_NAMESPACE = "kk-studio/harness/subagent/session/";
  private static final String CHILD_THREAD_NAMESPACE = "kk-studio/harness/subagent/thread/";
  private static final String COMMAND_NAMESPACE_PREFIX = "kk-studio/harness/subagent/command/";
  private static final int ACCEPT_ATTEMPTS = 3;
  static final String INVALID_RESUME_THREAD_MESSAGE =
      "This subagent thread is no longer valid. Dispatch a new task without thread_id.";

  private final Supplier<HarnessRuntime> runtimeProvider;
  private final AgentTaskService agentTasks;
  private final AgentBranchSettingsMaterializer settingsMaterializer;
  private final SubagentConfigProvider configProvider;

  public SubagentTaskRunner(
      Supplier<HarnessRuntime> runtimeProvider,
      AgentTaskService agentTasks,
      AgentBranchSettingsMaterializer settingsMaterializer,
      SubagentConfigProvider configProvider) {
    this.runtimeProvider = Objects.requireNonNull(runtimeProvider, "runtimeProvider");
    this.agentTasks = Objects.requireNonNull(agentTasks, "agentTasks");
    this.settingsMaterializer =
        Objects.requireNonNull(settingsMaterializer, "settingsMaterializer");
    this.configProvider = Objects.requireNonNull(configProvider, "configProvider");
  }

  @Override
  public SubagentTaskAcceptance accept(SubagentTaskRequest request) {
    Objects.requireNonNull(request, "request");
    HarnessRuntime runtime = requireRuntime();
    UUID childThreadId = childThreadId(request);
    String requestHash = requestHash(request);
    // 已接受的回放不依赖父调用是否仍在执行，因此先于父快照校验完成。
    SubagentTaskAcceptance replayed = replay(runtime, request, childThreadId, requestHash);
    if (replayed != null) {
      return replayed;
    }
    ParentInvocation parent = parentInvocation(runtime, request);
    if (request.resumeThreadId() != null) {
      requireResumeOwnership(runtime, request);
    }
    BranchSettings settings = materializeTarget(request);
    // 默认 maxTurns 是软预算默认值：一次接受内只物化一次，避免重试/回放改写既有 join 的预算。
    int maxTurns =
        request.maxTurns() != null
            ? request.maxTurns()
            : configProvider.subagentConfig().maxTurns();
    return submit(runtime, request, parent, settings, childThreadId, requestHash, maxTurns);
  }

  /**
   * 接受循环：每轮先重读 join，再构造命令 batch 并原子提交。
   *
   * <p>重读使并发同 invocation 的重复调用稳定回放已接受结果；cursor 冲突（子线程被其他输入推进）用重新读取的快照重试。重试耗尽后仍以最终一次 join
   * 重读为准，只有确实不存在匹配 join 时才向上报 rejected。
   */
  private SubagentTaskAcceptance submit(
      HarnessRuntime runtime,
      SubagentTaskRequest request,
      ParentInvocation parent,
      BranchSettings settings,
      UUID childThreadId,
      String requestHash,
      int maxTurns) {
    RuntimeException lastFailure = null;
    for (int attempt = 0; attempt < ACCEPT_ATTEMPTS; attempt++) {
      SubagentTaskAcceptance replayed = replay(runtime, request, childThreadId, requestHash);
      if (replayed != null) {
        return replayed;
      }
      try {
        AcceptedCommands accepted =
            agentTasks
                .submit(
                    task(runtime, request, parent, settings, childThreadId, requestHash, maxTurns))
                .accepted();
        return new SubagentTaskAcceptance(
            accepted.session().id(),
            accepted.thread().id(),
            accepted.replayed(),
            accepted.joinReplaced());
      } catch (RuntimeException failure) {
        if (failure instanceof DomainException domain) {
          if (domain.code() == DomainErrorCode.NOT_FOUND) {
            throw reject("subagent task parent thread no longer exists", domain);
          }
          if (domain.code() != DomainErrorCode.CONFLICT
              || Arrays.stream(HarnessConflictReason.values())
                  .noneMatch(reason -> reason.name().equals(domain.reason()))) {
            throw domain;
          }
        }
        // 并发胜者可能已经接受票据；先回放，但不能把任意失败当成可重试的游标冲突。
        SubagentTaskAcceptance concurrent;
        try {
          concurrent = replay(runtime, request, childThreadId, requestHash);
        } catch (RuntimeException replayFailure) {
          // 回放自身失败时保留提交失败，避免诊断只剩回放错误。
          replayFailure.addSuppressed(failure);
          throw replayFailure;
        }
        if (concurrent != null) {
          return concurrent;
        }
        boolean staleCursor =
            failure instanceof DomainException domain
                && domain.code() == DomainErrorCode.CONFLICT
                && HarnessConflictReason.STALE_COMMAND_CURSOR.name().equals(domain.reason());
        if (!staleCursor) {
          throw reject(message(failure), failure);
        }
        lastFailure = failure;
      }
    }
    SubagentTaskAcceptance replayed = replay(runtime, request, childThreadId, requestHash);
    if (replayed != null) {
      return replayed;
    }
    throw reject(message(lastFailure), lastFailure);
  }

  /** 既有 join 回放：只有父、目标子 Thread、请求 hash（含 agent/prompt/maxTurns 语义）与显式 maxTurns 全部一致才算同一次委派。 */
  private SubagentTaskAcceptance replay(
      HarnessRuntime runtime, SubagentTaskRequest request, UUID childThreadId, String requestHash) {
    ThreadJoin existing = runtime.findJoin(request.invocationId()).orElse(null);
    if (existing == null) {
      return null;
    }
    if (!Objects.equals(existing.parentThreadId(), request.parentThreadId())
        || !existing.childThreadId().equals(childThreadId)
        || !existing.requestHash().equals(requestHash)
        || !existing.agent().equals(request.subagentType())
        || (request.maxTurns() != null && !request.maxTurns().equals(existing.maxTurns()))) {
      throw reject("subagent task invocation was already accepted for a different delegation");
    }
    return new SubagentTaskAcceptance(
        sessionIdOf(runtime, childThreadId), childThreadId, true, false);
  }

  /**
   * 把委派翻译成 Agent 任务：新建时一次性携带 root settings、父关系与源 prompt；续接既有子 Thread 时只校验永久父关系后追加命令，忙碌子线程照常入队。
   *
   * <p>续接只追加 SET_AGENT + SET_EXECUTION + CUSTOM_MESSAGE；环境由执行根持有，新建与续接均不向子 Thread 写入环境。 SET_*
   * 前缀按固定顺序发出，batch 形状只由本次请求的目标 settings 决定、与子线程当时的 head settings 或执行根环境无关；因此同一次调用在任何重试 /
   * 并发顺序下都产生同一份命令指纹，Runtime 能精确识别为重放而不是「另一份委派」。
   */
  private static AgentTaskRequest task(
      HarnessRuntime runtime,
      SubagentTaskRequest request,
      ParentInvocation parent,
      BranchSettings settings,
      UUID childThreadId,
      String requestHash,
      int maxTurns) {
    SubagentConfig config = parent.config();
    SpawnRequest.Budget budget =
        new SpawnRequest.Budget(
            maxTurns,
            config.maxDepth(),
            config.maxConcurrency(),
            config.maxTotalConcurrency() == 0 ? Integer.MAX_VALUE : config.maxTotalConcurrency());
    if (request.resumeThreadId() == null) {
      return new AgentTaskRequest(
          request.invocationId(),
          childThreadId,
          derive(request.invocationId(), CHILD_SESSION_NAMESPACE),
          settings,
          List.of(
              command(
                  request.invocationId(),
                  0,
                  new CustomMessageCommandPayload(AgentMessage.user(request.prompt())))),
          AcceptancePreflight.IDENTITY,
          SpawnTrigger.MODEL_TOOL,
          parent.threadId(),
          parent.headEntryId(),
          null,
          false,
          budget,
          requestHash);
    }
    ThreadSnapshot child = requireChild(runtime, parent, childThreadId);
    List<ThreadCommandPayload> payloads =
        List.of(
            new SetAgentCommandPayload(settings.agentName()),
            new SetExecutionCommandPayload(settings.execution()),
            new CustomMessageCommandPayload(AgentMessage.user(request.prompt())));
    List<NewThreadCommand> commands = new ArrayList<>(payloads.size());
    for (int i = 0; i < payloads.size(); i++) {
      commands.add(command(request.invocationId(), i, payloads.get(i)));
    }
    return new AgentTaskRequest(
        request.invocationId(),
        childThreadId,
        null,
        settings,
        List.copyOf(commands),
        AcceptancePreflight.IDENTITY,
        SpawnTrigger.MODEL_TOOL,
        parent.threadId(),
        parent.headEntryId(),
        new SpawnRequest.Continuation(
            child.thread().headEntryId(), child.thread().nextCommandSequence()),
        false,
        budget,
        requestHash);
  }

  /** 续接既有子 Thread 前做所属权前置校验：跨父续接直接拒绝，不进入重试且不触发任何持久写入。 */
  private static void requireResumeOwnership(HarnessRuntime runtime, SubagentTaskRequest request) {
    ThreadSnapshot child;
    try {
      child = runtime.getThreadSnapshot(request.resumeThreadId());
    } catch (DomainException notFound) {
      if (notFound.code() != DomainErrorCode.NOT_FOUND) {
        throw notFound;
      }
      throw reject("subagent thread " + request.resumeThreadId() + " was not found", notFound);
    }
    if (!Objects.equals(request.parentThreadId(), child.thread().parentThreadId())) {
      throw reject(INVALID_RESUME_THREAD_MESSAGE);
    }
  }

  private static ThreadSnapshot requireChild(
      HarnessRuntime runtime, ParentInvocation parent, UUID childThreadId) {
    ThreadSnapshot child;
    try {
      child = runtime.getThreadSnapshot(childThreadId);
    } catch (DomainException notFound) {
      if (notFound.code() != DomainErrorCode.NOT_FOUND) {
        throw notFound;
      }
      throw reject("subagent thread " + childThreadId + " was not found", notFound);
    }
    if (!parent.threadId().equals(child.thread().parentThreadId())) {
      throw reject("subagent thread " + childThreadId + " does not belong to this parent");
    }
    return child;
  }

  /** 校验父调用仍是当前冻结的 tool invocation，并冻结其允许的 subagent 与 settings 事实。 */
  private ParentInvocation parentInvocation(HarnessRuntime runtime, SubagentTaskRequest request) {
    ThreadSnapshot parent;
    try {
      parent = runtime.getThreadSnapshot(request.parentThreadId());
    } catch (DomainException notFound) {
      if (notFound.code() != DomainErrorCode.NOT_FOUND) {
        throw notFound;
      }
      throw reject("task invocation is no longer attached to its parent Thread context", notFound);
    }
    if (parent.toolSiblings().stream()
        .noneMatch(tool -> tool.id().equals(request.invocationId()))) {
      throw reject("task invocation is no longer attached to its parent Thread context");
    }
    TurnStartPayload turnStart =
        parent
            .entryState()
            .openTurnStart()
            .map(
                entry -> {
                  if (!(entry.payload() instanceof TurnStartPayload start)) {
                    throw reject("parent turn start is not a valid turn capability snapshot");
                  }
                  return start;
                })
            .orElseThrow(() -> reject("parent turn has no open turn capability snapshot"));
    if (turnStart.capabilities() == null) {
      throw reject("parent turn has no frozen subagent capabilities");
    }
    List<SubagentBinding> allowed = turnStart.capabilities().subagentBindings();
    if (allowed.stream().noneMatch(binding -> binding.name().equals(request.subagentType()))) {
      throw reject(
          "subagent_type \""
              + request.subagentType()
              + "\" is not allowed; available: "
              + availableNames(allowed));
    }
    return new ParentInvocation(
        request.parentThreadId(), parent.thread().headEntryId(), configProvider.subagentConfig());
  }

  private BranchSettings materializeTarget(SubagentTaskRequest request) {
    try {
      return settingsMaterializer.materialize(request.subagentType());
    } catch (RuntimeException failure) {
      throw reject(message(failure), failure);
    }
  }

  private static UUID childThreadId(SubagentTaskRequest request) {
    return request.resumeThreadId() == null
        ? derive(request.invocationId(), CHILD_THREAD_NAMESPACE)
        : request.resumeThreadId();
  }

  private static UUID sessionIdOf(HarnessRuntime runtime, UUID childThreadId) {
    try {
      return runtime.getThreadSnapshot(childThreadId).thread().sessionId();
    } catch (DomainException notFound) {
      if (notFound.code() != DomainErrorCode.NOT_FOUND) {
        throw notFound;
      }
      throw reject("subagent thread " + childThreadId + " was not found", notFound);
    }
  }

  /** 命令幂等键只由 invocationId 与固定槽位派生：同一次调用的任何重试都命中同一批命令。 */
  private static NewThreadCommand command(
      UUID invocationId, int index, ThreadCommandPayload payload) {
    return new NewThreadCommand(
        payload,
        derive(invocationId, COMMAND_NAMESPACE_PREFIX + index + "/"),
        ThreadCommandPayloadJsonCodec.requestHash(payload));
  }

  /**
   * 规范请求身份：长度分隔拼接，区分「省略 maxTurns」与显式值，且不含运行期默认值。
   *
   * <p>因此目标 agent 的目录 settings 或进程默认 {@code maxTurns} 变化都不会改写已接受 join 的请求指纹。
   */
  static String requestHash(SubagentTaskRequest request) {
    MessageDigest digest = Sha256.newDigest();
    List<String> parts =
        List.of(
            request.parentThreadId().toString(),
            request.subagentType(),
            request.prompt(),
            request.maxTurns() == null ? "default" : "explicit:" + request.maxTurns(),
            request.resumeThreadId() == null ? "new" : "resume:" + request.resumeThreadId());
    for (String part : parts) {
      byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
      digest.update(
          new byte[] {
            (byte) (bytes.length >>> 24),
            (byte) (bytes.length >>> 16),
            (byte) (bytes.length >>> 8),
            (byte) bytes.length
          });
      digest.update(bytes);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  /** 由父 Tool invocation 稳定派生子身份：同一次调用重放得到同一 UUID。 */
  static UUID derive(UUID invocationId, String namespace) {
    return UUID.nameUUIDFromBytes((namespace + invocationId).getBytes(StandardCharsets.UTF_8));
  }

  private HarnessRuntime requireRuntime() {
    HarnessRuntime runtime = runtimeProvider.get();
    if (runtime == null) {
      throw reject("HarnessRuntime is not available");
    }
    return runtime;
  }

  private static String availableNames(List<SubagentBinding> bindings) {
    return bindings.stream()
        .map(SubagentBinding::name)
        .reduce((left, right) -> left + " / " + right)
        .orElse("none");
  }

  private static String message(Throwable failure) {
    if (failure == null) {
      return "subagent task was rejected without a cause";
    }
    String detail = failure.getMessage();
    return detail == null || detail.isBlank()
        ? "subagent task was rejected: " + failure.getClass().getSimpleName()
        : "subagent task was rejected: " + detail;
  }

  private static SubagentTaskRejectedException reject(String message) {
    return new SubagentTaskRejectedException(message);
  }

  private static SubagentTaskRejectedException reject(String message, Throwable cause) {
    return new SubagentTaskRejectedException(message, cause);
  }

  /** 校验并冻结的父调用上下文：允许的 subagent 已由调用方校验，这里冻结 settings 事实与当前额度参数。 */
  private record ParentInvocation(UUID threadId, UUID headEntryId, SubagentConfig config) {}
}
