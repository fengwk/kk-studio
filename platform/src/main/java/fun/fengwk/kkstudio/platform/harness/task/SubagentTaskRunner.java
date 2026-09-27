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
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinRequest;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** 校验父调用的冻结权限并提交原子的源命令 + join；额度、归属与投递由 Runtime 在树锁内裁决。 */
public class SubagentTaskRunner implements SubagentRunner {

  private static final String CHILD_SESSION_NAMESPACE = "kk-studio/harness/subagent/session/";
  private static final String CHILD_THREAD_NAMESPACE = "kk-studio/harness/subagent/thread/";
  private static final int CONTINUE_ATTEMPTS = 3;

  private final Supplier<HarnessRuntime> runtimeProvider;
  private final AgentBranchSettingsMaterializer settingsMaterializer;
  private final SubagentConfigProvider configProvider;

  public SubagentTaskRunner(
      Supplier<HarnessRuntime> runtimeProvider,
      AgentBranchSettingsMaterializer settingsMaterializer,
      SubagentConfigProvider configProvider) {
    this.runtimeProvider = Objects.requireNonNull(runtimeProvider, "runtimeProvider");
    this.settingsMaterializer =
        Objects.requireNonNull(settingsMaterializer, "settingsMaterializer");
    this.configProvider = Objects.requireNonNull(configProvider, "configProvider");
  }

  @Override
  public SubagentTaskAcceptance accept(SubagentTaskRequest request) {
    Objects.requireNonNull(request, "request");
    HarnessRuntime runtime = Objects.requireNonNull(runtimeProvider.get(), "HarnessRuntime");
    UUID childId =
        request.resumeThreadId() == null
            ? derive(request.invocationId(), CHILD_THREAD_NAMESPACE)
            : request.resumeThreadId();
    String hash = requestHash(request);
    ThreadJoin existing = runtime.findJoin(request.invocationId()).orElse(null);
    if (existing != null) {
      // invocationId 不是授权：父、完整请求和目标必须同时吻合，才能暴露已有子身份。
      if (!Objects.equals(existing.parentThreadId(), request.parentThreadId())
          || !existing.childThreadId().equals(childId)
          || !existing.requestHash().equals(hash)
          || !existing.agent().equals(request.subagentType())
          || (request.maxTurns() != null
              && !Objects.equals(existing.maxTurns(), request.maxTurns()))) {
        throw reject("subagent task invocation was already accepted for a different delegation");
      }
      return new SubagentTaskAcceptance(
          runtime.getThreadSnapshot(childId).thread().sessionId(), childId, true);
    }

    ThreadSnapshot parent = runtime.getThreadSnapshot(request.parentThreadId());
    if (parent.model() == null
        || parent.toolSiblings().stream()
            .noneMatch(tool -> tool.id().equals(request.invocationId()))) {
      throw reject("task invocation is no longer attached to its parent Thread context");
    }
    List<SubagentBinding> allowed = parent.model().requestSpec().subagentBindings();
    if (allowed.stream().noneMatch(binding -> binding.name().equals(request.subagentType()))) {
      throw reject("subagent_type \"" + request.subagentType() + "\" is not allowed");
    }
    SubagentConfig config = configProvider.subagentConfig();
    BranchSettings settings =
        settingsMaterializer.materializeSubagent(
            request.subagentType(), parent.entryPath().baseSettings().environmentName());
    ThreadJoinRequest join =
        new ThreadJoinRequest(
            request.invocationId(),
            request.parentThreadId(),
            parent.thread().headEntryId(),
            hash,
            request.subagentType(),
            request.maxTurns() == null ? config.maxTurns() : request.maxTurns(),
            config.maxDepth(),
            config.maxConcurrency(),
            config.maxTotalConcurrency() == 0 ? Integer.MAX_VALUE : config.maxTotalConcurrency());

    if (request.resumeThreadId() == null) {
      UUID sessionId = derive(request.invocationId(), CHILD_SESSION_NAMESPACE);
      AcceptedCommands accepted =
          runtime.acceptCommandsAndJoin(
              new AcceptCommandsCommand(
                  new AcceptCommandsTarget.NewSession(
                      sessionId,
                      childId,
                      settings,
                      request.parentThreadId(),
                      parent.thread().yoloEnabled()),
                  List.of(
                      command(
                          request.invocationId(),
                          0,
                          new UserMessageCommandPayload(AgentMessage.user(request.prompt()))))),
              join,
              AcceptancePreflight.IDENTITY);
      return new SubagentTaskAcceptance(
          accepted.session().id(), accepted.thread().id(), accepted.replayed());
    }

    for (int attempt = 0; attempt < CONTINUE_ATTEMPTS; attempt++) {
      ThreadSnapshot child = runtime.getThreadSnapshot(childId);
      if (!request.parentThreadId().equals(child.thread().parentThreadId())) {
        throw reject("subagent thread \"" + childId + "\" does not belong to this parent");
      }
      try {
        AcceptedCommands accepted =
            runtime.acceptCommandsAndJoin(
                new AcceptCommandsCommand(
                    new AcceptCommandsTarget.Thread(
                        childId,
                        child.thread().headEntryId(),
                        child.thread().nextCommandSequence()),
                    taskCommands(request, child.entryPath().baseSettings(), settings)),
                join,
                AcceptancePreflight.IDENTITY);
        return new SubagentTaskAcceptance(
            accepted.session().id(), accepted.thread().id(), accepted.replayed());
      } catch (HarnessRuntimeConflictException stale) {
        // 子线程可能仍在忙碌：重新读取 cursor 后追加，不等待旧 join 完成。
      }
    }
    throw reject("subagent thread changed before the task prompt could be queued");
  }

  private static List<NewThreadCommand> taskCommands(
      SubagentTaskRequest request, BranchSettings current, BranchSettings target) {
    List<ThreadCommandPayload> payloads = new ArrayList<>();
    if (!current.agentName().equals(target.agentName())) {
      payloads.add(new SetAgentCommandPayload(target.agentName()));
    }
    if (!current.model().equals(target.model())) {
      payloads.add(new SetModelCommandPayload(target.model()));
    }
    if (!Objects.equals(current.environmentName(), target.environmentName())) {
      payloads.add(new SetEnvironmentCommandPayload(target.environmentName()));
    }
    payloads.add(new UserMessageCommandPayload(AgentMessage.user(request.prompt())));
    List<NewThreadCommand> commands = new ArrayList<>(payloads.size());
    for (int i = 0; i < payloads.size(); i++) {
      commands.add(command(request.invocationId(), i, payloads.get(i)));
    }
    return List.copyOf(commands);
  }

  private static NewThreadCommand command(
      UUID invocationId, int index, ThreadCommandPayload payload) {
    return new NewThreadCommand(
        payload,
        derive(invocationId, "kk-studio/harness/subagent/command/" + index + "/"),
        ThreadCommandPayloadJsonCodec.requestHash(payload));
  }

  /** 长度分隔的规范请求身份；避免拼接歧义，区分省略 maxTurns 与显式值。 */
  static String requestHash(SubagentTaskRequest request) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String part :
          List.of(
              request.parentThreadId().toString(),
              request.subagentType(),
              request.prompt(),
              request.maxTurns() == null ? "default" : "explicit:" + request.maxTurns(),
              request.resumeThreadId() == null ? "new" : "resume:" + request.resumeThreadId())) {
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
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  static UUID derive(UUID invocationId, String namespace) {
    return UUID.nameUUIDFromBytes((namespace + invocationId).getBytes(StandardCharsets.UTF_8));
  }

  private static SubagentTaskRejectedException reject(String message) {
    return new SubagentTaskRejectedException(message);
  }
}
