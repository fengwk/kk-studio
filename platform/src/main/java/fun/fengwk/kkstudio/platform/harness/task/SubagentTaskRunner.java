package fun.fengwk.kkstudio.platform.harness.task;

import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfig;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfigProvider;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentRunner;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskAcceptance;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskRequest;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.SubagentContext;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.harness.task.repo.SubagentTaskRepository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 以普通 durable Harness Thread 持久接受 {@code task} 委派的 {@link SubagentRunner} 实现。
 *
 * <p>接受是单事务的持久动作：子 Session/Thread 的命令接受与本次委派记录在同一个 store 事务内提交（记录通过 {@link AcceptancePreflight}
 * 写入，因此不重入 store 事务）。接受即返回子 Thread 身份，不等待、不轮询、不保留阻塞线程；子执行结清后的结果交付由 {@link
 * SubagentTaskSettlementScanner} 完成。
 *
 * <p>关键不变量：
 *
 * <ul>
 *   <li>幂等：{@code invocationId} 是主键；同一次 Tool 调用重试先查持久记录，命中即返回同一个子 Thread，绝不双开。
 *   <li>稳定派生：新建委派的子 Session/Thread UUID 由父 Tool invocation 稳定派生，重放得到同一身份。
 *   <li>归属与静止：继续既有子 Thread 必须属于当前父、当前根，处于静止态，且上一次执行已完整结清并交付（没有未交付的 OPEN/SETTLED 遗留）；同子 Thread 至多一行
 *       OPEN（数据库部分唯一索引）。
 *   <li>额度来自持久事实：父级/树级并发额度按未结清记录统计，不以内存 registry 为权威；统计与写入在同一事务内通过事务级 advisory 锁串行化， 并发接受不会同时越过上限。
 * </ul>
 */
public class SubagentTaskRunner implements SubagentRunner {

  private static final String CHILD_SESSION_NAMESPACE = "kk-studio/harness/subagent/session/";
  private static final String CHILD_THREAD_NAMESPACE = "kk-studio/harness/subagent/thread/";
  private static final int CONTINUE_ATTEMPTS = 3;

  private final Supplier<HarnessRuntime> runtimeProvider;
  private final AgentBranchSettingsMaterializer settingsMaterializer;
  private final SubagentConfigProvider configProvider;
  private final SubagentTaskRepository repository;

  public SubagentTaskRunner(
      Supplier<HarnessRuntime> runtimeProvider,
      AgentBranchSettingsMaterializer settingsMaterializer,
      SubagentConfigProvider configProvider,
      SubagentTaskRepository repository) {
    this.runtimeProvider = Objects.requireNonNull(runtimeProvider, "runtimeProvider");
    this.settingsMaterializer =
        Objects.requireNonNull(settingsMaterializer, "settingsMaterializer");
    this.configProvider = Objects.requireNonNull(configProvider, "configProvider");
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  @Override
  public SubagentTaskAcceptance accept(SubagentTaskRequest request) {
    Objects.requireNonNull(request, "request");
    HarnessRuntime runtime = requireRuntime();
    SubagentTask existing = repository.findByInvocationId(request.invocationId());
    if (existing != null) {
      return replay(existing, request);
    }
    ParentContext parent = parentContext(runtime, request);
    SubagentBinding selected = selectBinding(parent, request.subagentType());
    // 额度不在事务外预检：并发接受会各自读到旧计数而同时越限，额度必须在写入事务内加锁后判定。
    return request.resumeThreadId() == null
        ? createChild(runtime, parent, request, selected)
        : continueChild(runtime, parent, request, selected);
  }

  /**
   * 幂等重放：只有"同一次委派"才能按 {@code invocationId} 复用既有子执行。
   *
   * <p>{@code invocationId} 只是调用方给出的键，不能单独作为授权依据。父 Thread、目标 Agent、prompt 与 {@code max_turns}
   * 任一不同，或本次要求的目标子 Thread 与既有记录不一致（新建要求稳定派生的子 Thread，继续要求就是那条既有子 Thread），都说明这不是同一次 Tool
   * 调用的重试，而是另一份委派（重复使用他人 invocation 或客户端串号）。此时必须显式失败，绝不返回别人的子 Thread 身份：否则调用方会越权看到/继续一个不属于它本次指令的执行。
   *
   * <p>重放路径不读 Runtime、不碰额度、不写记录：同一份持久事实只能被回放，不能被改写。
   */
  private static SubagentTaskAcceptance replay(SubagentTask existing, SubagentTaskRequest request) {
    UUID expectedChildThreadId =
        request.resumeThreadId() == null
            ? derive(request.invocationId(), CHILD_THREAD_NAMESPACE)
            : request.resumeThreadId();
    boolean sameDelegation =
        existing.parentThreadId().equals(request.parentThreadId())
            && existing.agent().equals(request.subagentType())
            && existing.prompt().equals(request.prompt())
            && Objects.equals(existing.maxTurns(), request.maxTurns())
            && existing.childThreadId().equals(expectedChildThreadId);
    if (!sameDelegation) {
      throw reject(
          "subagent task invocation "
              + request.invocationId()
              + " was already accepted for a different delegation");
    }
    return new SubagentTaskAcceptance(existing.childSessionId(), existing.childThreadId(), true);
  }

  /** 新建子 Session：预分配稳定派生的身份，携带 SubagentContext 冻结归属。 */
  private SubagentTaskAcceptance createChild(
      HarnessRuntime runtime,
      ParentContext parent,
      SubagentTaskRequest request,
      SubagentBinding selected) {
    UUID childSessionId = derive(request.invocationId(), CHILD_SESSION_NAMESPACE);
    UUID childThreadId = derive(request.invocationId(), CHILD_THREAD_NAMESPACE);
    BranchSettings settings =
        settingsMaterializer.materializeSubagent(selected.name(), parent.environmentName());
    SubagentContext subagentContext =
        new SubagentContext(
            parent.parentThreadId(),
            parent.rootThreadId(),
            request.invocationId(),
            parent.depth() + 1);
    AcceptedCommands accepted;
    try {
      accepted =
          runtime.acceptCommands(
              new AcceptCommandsCommand(
                  new AcceptCommandsTarget.NewSession(
                      childSessionId,
                      childThreadId,
                      settings,
                      subagentContext,
                      parent.yoloEnabled()),
                  List.of(
                      command(new UserMessageCommandPayload(AgentMessage.user(request.prompt()))))),
              insertTaskPreflight(parent, request, childSessionId, childThreadId));
    } catch (HarnessRuntimeConflictException conflict) {
      throw new SubagentTaskRejectedException(
          "subagent session changed before the task prompt could be queued", conflict);
    }
    return new SubagentTaskAcceptance(
        accepted.session().id(), accepted.thread().id(), accepted.replayed());
  }

  /** 继续既有子 Thread：校验归属与静止态后，用目标 Agent 的配置收敛 settings 并入队本次 prompt。 */
  private SubagentTaskAcceptance continueChild(
      HarnessRuntime runtime,
      ParentContext parent,
      SubagentTaskRequest request,
      SubagentBinding selected) {
    UUID childThreadId = request.resumeThreadId();
    BranchSettings target =
        settingsMaterializer.materializeSubagent(selected.name(), parent.environmentName());
    for (int attempt = 0; attempt < CONTINUE_ATTEMPTS; attempt++) {
      ThreadSnapshot child = requireOwnedQuiescentChild(runtime, parent, childThreadId);
      List<NewThreadCommand> commands =
          taskCommands(child.entryPath().baseSettings(), target, request.prompt());
      try {
        AcceptedCommands accepted =
            runtime.acceptCommands(
                new AcceptCommandsCommand(
                    new AcceptCommandsTarget.Thread(
                        childThreadId,
                        child.thread().headEntryId(),
                        child.thread().nextCommandSequence()),
                    commands),
                insertTaskPreflight(parent, request, child.thread().sessionId(), childThreadId));
        return new SubagentTaskAcceptance(
            accepted.session().id(), accepted.thread().id(), accepted.replayed());
      } catch (HarnessRuntimeConflictException conflict) {
        // 子 Thread 在读取与接受之间推进：重读最新 head/sequence 后重试，额度与归属仍按持久事实校验。
      }
    }
    throw new SubagentTaskRejectedException(
        "subagent thread changed before the task prompt could be queued");
  }

  /**
   * 任务记录的写入 preflight：在同一 store 事务内串行化额度校验、复核父 Thread 事实、确认子执行没有未交付遗留并插入委派记录。
   *
   * <p>额度必须与插入同事务：{@link SubagentTaskRepository#lockQuota} 先取事务级 advisory
   * 锁，随后计数与插入都发生在锁内，因此并发接受不会同时越过 {@code maxConcurrency}/{@code
   * maxTotalConcurrency}。锁在事务结束（提交或回滚）时自动释放，无需清理；校验失败抛异常即回滚整个接受事务，不留下半成品子 Session/Thread。
   *
   * <p>只在全新批次被调用（精确重放不会调用），因此不会重复插入；插入失败（同子 Thread 已有 OPEN 行）使整个接受事务回滚。
   *
   * <p>三条事务内复核：
   *
   * <ul>
   *   <li>父 Thread 仍然存在且 head 没有推进：父在本次接受之前被停止或已推进过 turn 时，这次委派是在旧事实上迟到的， 必须拒绝而不是留下一个父已不再等待的 OPEN
   *       记录。
   *   <li>父 Thread 的 head 不是显式停止边界：被显式停止的父不接受新委派（停止门禁在接受侧同样成立）。
   *   <li>目标子 Thread 没有未交付的旧执行：继续委派要求上一次执行已完整结清并交付，否则旧结果与新 prompt 会在同一子 Thread
   *       上串扰（父子都无法判断哪条结果属于哪次执行）。
   * </ul>
   *
   * <p>这里对父 Thread 的读取是事务内的**快照读**而不是加锁复核：本事务已按规范锁序锁住目标 Session/Thread，Harness Store 的锁 rank 单调性与
   * Thread 锁按 id 升序规则都不允许再取得父 Thread 的锁。因此它是接受侧的有界门禁，交付侧的父停止门禁由 {@code
   * SubagentTaskSettlementScanner.deliveryPreflight} 在持有父 Thread 锁时原子完成。
   */
  private AcceptancePreflight insertTaskPreflight(
      ParentContext parent, SubagentTaskRequest request, UUID childSessionId, UUID childThreadId) {
    return (tx, session, commands) -> {
      repository.lockQuota(parent.rootThreadId(), parent.parentThreadId());
      requireQuota(parent);
      ThreadState parentThread =
          tx.findThread(parent.parentThreadId())
              .orElseThrow(
                  () -> reject("parent thread " + parent.parentThreadId() + " no longer exists"));
      if (!parentThread.headEntryId().equals(parent.parentHeadEntryId())) {
        throw reject("parent thread advanced before the delegation could be accepted");
      }
      Entry parentHead =
          tx.findEntry(parentThread.headEntryId())
              .orElseThrow(
                  () -> reject("parent thread " + parent.parentThreadId() + " has no head entry"));
      if (SubagentTaskTerminalProjection.stoppedHead(parentHead)) {
        throw reject("parent thread is explicitly stopped");
      }
      if (repository.hasUndeliveredByChildThreadId(childThreadId)) {
        throw reject(
            "subagent thread "
                + childThreadId
                + " still has an undelivered execution; wait for its result before continuing");
      }
      ThreadState childThread =
          tx.findThread(childThreadId)
              .orElseThrow(
                  () ->
                      new SubagentTaskRejectedException(
                          "subagent thread " + childThreadId + " was not created"));
      SubagentTaskDraft draft =
          new SubagentTaskDraft(
              request.invocationId(),
              parent.parentThreadId(),
              parent.rootThreadId(),
              childSessionId,
              childThreadId,
              childThread.headEntryId(),
              request.subagentType(),
              request.prompt(),
              request.maxTurns(),
              SubagentTaskStatus.OPEN,
              0L);
      try {
        if (!repository.insert(draft)) {
          throw new SubagentTaskRejectedException("subagent task record could not be created");
        }
      } catch (SubagentTaskRejectedException rejected) {
        throw rejected;
      } catch (RuntimeException conflict) {
        // 主键冲突（同 invocation 重放）或部分唯一索引冲突（同子 Thread 已有 OPEN 执行）。
        throw new SubagentTaskRejectedException(
            "subagent thread " + childThreadId + " already has an open execution", conflict);
      }
      return commands;
    };
  }

  private ParentContext parentContext(HarnessRuntime runtime, SubagentTaskRequest request) {
    // head 由接受事务复核：父在接受之前被停止/推进时，这次委派是在旧事实上迟到的。
    UUID parentThreadId = request.parentThreadId();
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(parentThreadId);
    if (snapshot.model() == null) {
      throw reject("task invocation is no longer attached to its parent Thread context");
    }
    snapshot.toolSiblings().stream()
        .filter(tool -> tool.id().equals(request.invocationId()))
        .findFirst()
        .orElseThrow(
            () -> reject("task invocation is no longer attached to its parent Thread context"));
    RootPayload root = (RootPayload) snapshot.entryPath().root().payload();
    int depth = root.subagentContext() == null ? 1 : root.subagentContext().depth();
    SubagentConfig config = configProvider.subagentConfig();
    if (depth >= config.maxDepth()) {
      throw reject("subagent max depth reached: " + depth + "/" + config.maxDepth());
    }
    List<SubagentBinding> allowed = snapshot.model().requestSpec().subagentBindings();
    if (allowed.isEmpty()) {
      throw reject("the frozen parent invocation does not allow subagent delegation");
    }
    UUID rootThreadId =
        root.subagentContext() == null ? parentThreadId : root.subagentContext().rootThreadId();
    return new ParentContext(
        parentThreadId,
        rootThreadId,
        depth,
        allowed,
        snapshot.entryPath().baseSettings().environmentName(),
        snapshot.thread().yoloEnabled(),
        snapshot.thread().headEntryId());
  }

  private static SubagentBinding selectBinding(ParentContext parent, String subagentType) {
    return parent.allowedSubagents().stream()
        .filter(binding -> binding.name().equals(subagentType))
        .findFirst()
        .orElseThrow(
            () ->
                reject(
                    "subagent_type \""
                        + subagentType
                        + "\" is not allowed; available: "
                        + availableNames(parent.allowedSubagents())));
  }

  /** 额度只按持久未结清记录统计：父级并发与（可选的）树级总量。 */
  private void requireQuota(ParentContext parent) {
    SubagentConfig config = configProvider.subagentConfig();
    int openByParent = repository.countOpenByParentThreadId(parent.parentThreadId());
    if (openByParent >= config.maxConcurrency()) {
      throw reject(
          "subagent concurrency limit reached for this parent: "
              + openByParent
              + "/"
              + config.maxConcurrency());
    }
    if (config.maxTotalConcurrency() > 0) {
      int openByRoot = repository.countOpenByRootThreadId(parent.rootThreadId());
      if (openByRoot >= config.maxTotalConcurrency()) {
        throw reject(
            "subagent total concurrency limit reached for this delegation tree: "
                + openByRoot
                + "/"
                + config.maxTotalConcurrency());
      }
    }
  }

  private ThreadSnapshot requireOwnedQuiescentChild(
      HarnessRuntime runtime, ParentContext parent, UUID childThreadId) {
    ThreadSnapshot snapshot;
    try {
      snapshot = runtime.getThreadSnapshot(childThreadId);
    } catch (HarnessRuntimeNotFoundException notFound) {
      throw reject("subagent thread \"" + childThreadId + "\" was not found");
    }
    RootPayload root = (RootPayload) snapshot.entryPath().root().payload();
    SubagentContext context = root.subagentContext();
    if (context == null
        || !context.parentThreadId().equals(parent.parentThreadId())
        || !context.rootThreadId().equals(parent.rootThreadId())) {
      throw reject("subagent thread \"" + childThreadId + "\" does not belong to this parent");
    }
    if (snapshot.model() != null
        || !snapshot.toolSiblings().isEmpty()
        || !snapshot.queuedCommands().isEmpty()
        || (snapshot.entryPath().head().payload() instanceof TurnEndPayload end
            && end.continueModel())) {
      throw reject("subagent thread \"" + childThreadId + "\" is not quiescent");
    }
    return snapshot;
  }

  /**
   * 继续子会话的配置收敛命令前缀：{@code SET_AGENT -> SET_MODEL -> SET_ENVIRONMENT -> USER}。
   *
   * <p>环境只有在与当前目标不同（含清除为 null）时才发送 {@code SET_ENVIRONMENT}。
   */
  private static List<NewThreadCommand> taskCommands(
      BranchSettings current, BranchSettings target, String prompt) {
    List<NewThreadCommand> commands = new ArrayList<>();
    if (!current.agentName().equals(target.agentName())) {
      commands.add(command(new SetAgentCommandPayload(target.agentName())));
    }
    if (!current.model().equals(target.model())) {
      commands.add(command(new SetModelCommandPayload(target.model())));
    }
    if (!Objects.equals(current.environmentName(), target.environmentName())) {
      commands.add(command(new SetEnvironmentCommandPayload(target.environmentName())));
    }
    commands.add(command(new UserMessageCommandPayload(AgentMessage.user(prompt))));
    return List.copyOf(commands);
  }

  private static NewThreadCommand command(ThreadCommandPayload payload) {
    return new NewThreadCommand(
        payload, UUID.randomUUID(), ThreadCommandPayloadJsonCodec.requestHash(payload));
  }

  /** 由父 Tool invocation 稳定派生子身份：同一次调用重放得到同一 UUID。 */
  static UUID derive(UUID invocationId, String namespace) {
    return UUID.nameUUIDFromBytes((namespace + invocationId).getBytes(StandardCharsets.UTF_8));
  }

  private HarnessRuntime requireRuntime() {
    HarnessRuntime runtime = runtimeProvider.get();
    if (runtime == null) {
      throw new SubagentTaskRejectedException("HarnessRuntime is not available");
    }
    return runtime;
  }

  private static String availableNames(List<SubagentBinding> bindings) {
    return bindings.stream()
        .map(SubagentBinding::name)
        .reduce((left, right) -> left + " / " + right)
        .orElse("none");
  }

  private static SubagentTaskRejectedException reject(String message) {
    return new SubagentTaskRejectedException(message);
  }

  /** 校验并冻结的父调用上下文；{@code parentHeadEntryId} 供接受事务复核父尚未推进。 */
  private record ParentContext(
      UUID parentThreadId,
      UUID rootThreadId,
      int depth,
      List<SubagentBinding> allowedSubagents,
      String environmentName,
      boolean yoloEnabled,
      UUID parentHeadEntryId) {}
}
