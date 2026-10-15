package fun.fengwk.kkstudio.platform.harness.prompt;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.ChangeGate;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.HarnessThreadChangeSource;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.join.JoinPurpose;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinOutcome;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinRequest;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.SystemReminder;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.platform.harness.task.AgentBranchSettingsMaterializer;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptContent;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptResources;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptTasks;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Prompt 任务窄端口的宿主实现：为一次调用创建 root Session/Thread、与 TASK join 原子接受源 Prompt，并观察固定 join 的终态报告。
 *
 * <p>插件只提交受信 system 文本与有序内容片段。宿主把受信文本包裹成前导 {@code <system-reminder>} 段，与内容片段合成唯一一条 USER 命令；媒体片段在
 * 接受事务内按捕获位置物化为 durable RESOURCE，因此提交前的命令形态只含 durable-safe 文本与媒体占位。
 *
 * <p>任务句柄就是可持久化的 root Thread id：插件持久化它即可在重启后恢复同一 join 与同一固定回执。
 */
@Component
public final class PluginPromptTaskService implements PluginPromptTasks {

  /** timed signal wait 同时兼做 caller-active/deadline 检查的切片：无事件时不读 snapshot，但取消/超时最迟在该切片内被察觉。 */
  private static final long CALLER_CHECK_NANOS = Duration.ofMillis(100).toNanos();

  /** stop 的 CAS 重读上限：只有真正的 stale version 才重试，耗尽后受控失败而不是静默返回。 */
  private static final int STOP_ATTEMPTS = 3;

  private final ObjectProvider<HarnessRuntime> runtimes;
  private final AgentBranchSettingsMaterializer settingsMaterializer;
  private final HarnessThreadChangeSource changeSource;
  private final PluginPromptResourceBridge resources;

  @Autowired
  public PluginPromptTaskService(
      ObjectProvider<HarnessRuntime> runtimes,
      AgentBranchSettingsMaterializer settingsMaterializer,
      HarnessThreadChangeSource changeSource,
      PluginPromptResourceBridge resources) {
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    this.settingsMaterializer =
        Objects.requireNonNull(settingsMaterializer, "settingsMaterializer");
    this.changeSource = Objects.requireNonNull(changeSource, "changeSource");
    this.resources = Objects.requireNonNull(resources, "resources");
  }

  /**
   * 任务句柄的 join invocation 确定性派生。
   *
   * <p>派生命名空间在可持久化句柄上保持稳定：已经持久化了句柄的调用方在升级或重启后仍能恢复同一 join 与固定回执。
   */
  public static UUID invocationId(UUID taskId) {
    Objects.requireNonNull(taskId, "taskId");
    return UUID.nameUUIDFromBytes(
        ("kk-studio/harness/oneshot/" + taskId).getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public UUID submit(String agentName, String systemText, List<PluginPromptContent> contents) {
    Objects.requireNonNull(agentName, "agentName");
    Objects.requireNonNull(contents, "contents");
    if (contents.isEmpty()) {
      throw new IllegalArgumentException("prompt contents must not be empty");
    }
    List<PluginPromptContent> ordered = List.copyOf(contents);
    HarnessRuntime runtime = requireRuntime();
    var settings = settingsMaterializer.materialize(agentName);
    // request hash 只基于 durable-safe 的文本/媒体占位形态计算：瞬时流与宿主消息结构不进入 command codec。
    CustomMessageCommandPayload payload =
        new CustomMessageCommandPayload(placeholderMessage(systemText, ordered));
    NewThreadCommand command = new NewThreadCommand(payload, UUID.randomUUID());
    UUID taskId = UUID.randomUUID();
    runtime.acceptCommandsAndJoin(
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewRootSession(UUID.randomUUID(), taskId, settings, false),
            List.of(command)),
        joinRequest(taskId, agentName, command.requestHash()),
        preflight(systemText, ordered));
    return taskId;
  }

  @Override
  public String await(UUID taskId, Duration timeout, BooleanSupplier continueWaiting) {
    Objects.requireNonNull(taskId, "taskId");
    Duration boundedTimeout = requirePositive(timeout, "timeout");
    Objects.requireNonNull(continueWaiting, "continueWaiting");
    long timeoutNanos = boundedTimeout.toNanos();
    UUID invocationId = invocationId(taskId);
    HarnessRuntime runtime = requireRuntime();
    long startedAt = System.nanoTime();
    ChangeGate gate = new ChangeGate();
    // 订阅必须在 success/error/timeout/cancel/interruption 全路径释放。
    try (HarnessThreadChangeSource.Subscription subscription =
        changeSource.subscribe(taskId, gate::version)) {
      ChangeGate.State since = gate.snapshot();
      boolean first = true;
      while (true) {
        if (!continueWaiting.getAsBoolean()) {
          stop(runtime, taskId);
          throw new IllegalStateException("prompt task caller is no longer active");
        }
        ChangeGate.State current = gate.snapshot();
        boolean versionWake = first || current.version() != since.version();
        since = current;
        first = false;
        if (versionWake) {
          // 终态优先：订阅后的首次权威读取与 version/resync 唤醒后都先检查 terminal，再判 timeout。
          String result = resultIfFinished(runtime, invocationId);
          if (result != null) {
            return result;
          }
        }
        // 相对 elapsed 计时：remaining 非负、无绝对 deadline，避免 nanoTime + timeout 溢出。
        long remaining = timeoutNanos - (System.nanoTime() - startedAt);
        if (remaining <= 0L) {
          // deadline 前的最终权威读：唤醒信号只是提示，漏通知/竞态不能把已匹配的结果误报为 timeout。
          String result = resultIfFinished(runtime, invocationId);
          if (result != null) {
            return result;
          }
          stop(runtime, taskId);
          throw new IllegalStateException("prompt task timed out after " + timeout);
        }
        long sliceNanos = Math.min(remaining, CALLER_CHECK_NANOS);
        try {
          gate.awaitChange(since, sliceNanos);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(
              "prompt task observation thread was interrupted", interrupted);
        }
      }
    }
  }

  @Override
  public void stop(UUID taskId) {
    Objects.requireNonNull(taskId, "taskId");
    stop(requireRuntime(), taskId);
  }

  /**
   * 唯一的 USER 命令：可信 system 文本是前导 reminder 内容，其余内容按插件给定顺序展开。
   *
   * <p>媒体只以暂存句柄的 durable 身份占位，绝不把瞬时流或宿主 RESOURCE 结构交给 command codec。
   */
  private static AgentMessage placeholderMessage(
      String systemText, List<PluginPromptContent> contents) {
    List<AgentMessageContent> shaped = new ArrayList<>(contents.size() + 1);
    String reminder = reminderText(systemText);
    if (reminder != null) {
      shaped.add(new TextMessageContent(reminder));
    }
    for (PluginPromptContent content : contents) {
      switch (content) {
        case PluginPromptContent.Text text -> shaped.add(new TextMessageContent(text.text()));
        case PluginPromptContent.Media media -> shaped.add(
            new TextMessageContent(mediaPlaceholder(media.resource())));
      }
    }
    return new AgentMessage(AgentMessageRole.USER, shaped);
  }

  /** 接受事务内的 durable 形态：按捕获的内容位置把媒体物化为 RESOURCE，不解析任何插件文本。 */
  private AcceptancePreflight preflight(String systemText, List<PluginPromptContent> contents) {
    return (tx, session, commands) -> {
      if (commands.size() != 1
          || !(commands.getFirst().payload() instanceof CustomMessageCommandPayload)) {
        throw new IllegalStateException(
            "prompt task acceptance received an unexpected command batch");
      }
      return List.of(
          commands
              .getFirst()
              .withPayload(
                  new CustomMessageCommandPayload(
                      durableMessage(session.id(), systemText, contents))));
    };
  }

  private AgentMessage durableMessage(
      UUID sessionId, String systemText, List<PluginPromptContent> contents) {
    List<AgentMessageContent> durable = new ArrayList<>(contents.size() + 1);
    String reminder = reminderText(systemText);
    if (reminder != null) {
      durable.add(new TextMessageContent(reminder));
    }
    for (PluginPromptContent content : contents) {
      switch (content) {
        case PluginPromptContent.Text text -> durable.add(new TextMessageContent(text.text()));
        case PluginPromptContent.Media media -> durable.add(
            resources.accept(sessionId, media.resource()));
      }
    }
    return new AgentMessage(AgentMessageRole.USER, durable);
  }

  /** 可信 system 文本用 {@code <system-reminder>} 包裹，与运行时注入的上下文提醒同形；空白表示不注入。 */
  private static String reminderText(String systemText) {
    if (systemText == null || systemText.isBlank()) {
      return null;
    }
    return SystemReminder.wrap(systemText) + "\n\n";
  }

  /** 媒体占位：只由暂存句柄的 durable 身份构成，同一内容永远得到同一 request hash。 */
  private static String mediaPlaceholder(PluginPromptResources.StagedResource resource) {
    return "<staged-media uploadId=" + resource.uploadId() + " blobId=" + resource.blobId() + ">";
  }

  /** root completion ticket：parent 为空（不投递父消息），但与源 prompt 在同一事务内原子接受。 */
  private static ThreadJoinRequest joinRequest(UUID taskId, String agentName, String requestHash) {
    return new ThreadJoinRequest(
        invocationId(taskId),
        null,
        null,
        requestHash,
        agentName,
        null,
        1,
        1,
        Integer.MAX_VALUE,
        JoinPurpose.TASK);
  }

  /** 读取固定 join 的终态；未匹配返回 null，已匹配则返回报告或抛出具名受控失败。 */
  private static String resultIfFinished(HarnessRuntime runtime, UUID invocationId) {
    return runtime
        .projectJoinReceipt(invocationId)
        .map(PluginPromptTaskService::terminalText)
        .orElse(null);
  }

  /** 非 COMPLETED 只声明受控 outcome，绝不拼 receipt.error、远端 URI 或凭据；空白报告明确失败，成功仍 trim。 */
  private static String terminalText(ThreadJoinReceipt receipt) {
    if (receipt.outcome() != ThreadJoinOutcome.COMPLETED) {
      throw new IllegalStateException("prompt task ended with " + receipt.outcome());
    }
    String text = receipt.report();
    if (text == null || text.isBlank()) {
      throw new IllegalStateException("prompt task returned no Assistant text");
    }
    return text.trim();
  }

  private void stop(HarnessRuntime runtime, UUID taskId) {
    for (int attempt = 0; attempt < STOP_ATTEMPTS; attempt++) {
      ThreadSnapshot snapshot;
      try {
        snapshot = runtime.getThreadSnapshot(taskId);
      } catch (HarnessRuntimeNotFoundException gone) {
        // 任务已经不存在：stop 容忍，不再重试。
        return;
      }
      try {
        runtime.stop(new StopCommand(taskId, UUID.randomUUID(), snapshot.thread().version()));
        return;
      } catch (HarnessRuntimeConflictException conflict) {
        if (conflict.reason() != HarnessRuntimeConflictException.Reason.STALE_VERSION) {
          // 只有真正的 stale version 才是有界 CAS 重读；其它冲突必须明确失败。
          throw conflict;
        }
      } catch (HarnessRuntimeNotFoundException gone) {
        return;
      }
    }
    throw new IllegalStateException(
        "prompt task stop did not converge after " + STOP_ATTEMPTS + " stale version retries");
  }

  private HarnessRuntime requireRuntime() {
    HarnessRuntime runtime = runtimes.getIfAvailable();
    if (runtime == null) {
      throw new IllegalStateException("harness runtime is not available in this deployment");
    }
    return runtime;
  }

  private static Duration requirePositive(Duration value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(field + " must be positive");
    }
    try {
      value.toNanos();
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException(
          field + " is too large to express in nanoseconds", overflow);
    }
    return value;
  }
}
