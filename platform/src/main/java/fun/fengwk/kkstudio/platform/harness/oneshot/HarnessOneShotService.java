package fun.fengwk.kkstudio.platform.harness.oneshot;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.ChangeGate;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.HarnessThreadChangeSource;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.platform.harness.task.AgentBranchSettingsMaterializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** 面向内部编排的一次性 Harness 调用器。它创建 root Thread、原子提交 SYSTEM/USER 消息、观察终态并提取最后一条 Assistant 文本。 */
@Component
public final class HarnessOneShotService {

  /** timed signal wait 同时兼做 caller-active/deadline 检查的切片：无事件时不读 snapshot，但取消/超时最迟在该切片内被察觉。 */
  private static final long CALLER_CHECK_NANOS = Duration.ofMillis(100).toNanos();

  private final ObjectProvider<HarnessRuntime> runtimes;
  private final AgentBranchSettingsMaterializer settingsMaterializer;
  private final HarnessThreadChangeSource changeSource;

  @Autowired
  public HarnessOneShotService(
      ObjectProvider<HarnessRuntime> runtimes,
      AgentBranchSettingsMaterializer settingsMaterializer,
      HarnessThreadChangeSource changeSource) {
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    this.settingsMaterializer =
        Objects.requireNonNull(settingsMaterializer, "settingsMaterializer");
    this.changeSource = Objects.requireNonNull(changeSource, "changeSource");
  }

  public UUID submit(
      String agentName,
      EnvironmentBinding environment,
      String systemMessage,
      AgentMessage userMessage) {
    return submit(agentName, environment, systemMessage, userMessage, AcceptancePreflight.IDENTITY);
  }

  /**
   * 带 media preflight 的一次性提交：NEW_SESSION 一次原子物化 Session/Thread/Command/Work（preflight 在全新接受时把 USER
   * 消息中按 manifest 顺序的占位内容物化为 durable 内容，如 H3 的全局存储 RESOURCE）；preflight 必须保持
   * clientCommandId/requestHash 不变。CUSTOM_MESSAGE 请求 hash 只基于 durable 形态计算，因此 USER 消息在提交时不得携带 瞬时
   * media/attachment 内容。
   */
  public UUID submit(
      String agentName,
      EnvironmentBinding environment,
      String systemMessage,
      AgentMessage userMessage,
      AcceptancePreflight preflight) {
    Objects.requireNonNull(userMessage, "userMessage");
    Objects.requireNonNull(preflight, "preflight");
    if (userMessage.role() != AgentMessageRole.USER) {
      throw new IllegalArgumentException("one-shot userMessage must use USER role");
    }
    HarnessRuntime runtime = requireRuntime();
    var settings = settingsMaterializer.materialize(agentName, environment);
    CustomMessageCommandPayload systemPayload =
        new CustomMessageCommandPayload(AgentMessage.system(systemMessage));
    CustomMessageCommandPayload userPayload = new CustomMessageCommandPayload(userMessage);
    NewThreadCommand systemCommand =
        new NewThreadCommand(
            systemPayload,
            UUID.randomUUID(),
            ThreadCommandPayloadJsonCodec.requestHash(systemPayload));
    NewThreadCommand userCommand =
        new NewThreadCommand(
            userPayload, UUID.randomUUID(), ThreadCommandPayloadJsonCodec.requestHash(userPayload));
    AcceptedCommands accepted =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewSession(
                    UUID.randomUUID(), UUID.randomUUID(), settings, null, false),
                List.of(systemCommand, userCommand)),
            preflight);
    return accepted.thread().id();
  }

  public String await(UUID threadId, Duration timeout, BooleanSupplier continueWaiting) {
    Objects.requireNonNull(threadId, "threadId");
    Duration boundedTimeout = requirePositive(timeout, "timeout");
    Objects.requireNonNull(continueWaiting, "continueWaiting");
    long timeoutNanos = boundedTimeout.toNanos();
    HarnessRuntime runtime = requireRuntime();
    long startedAt = System.nanoTime();
    ChangeGate gate = new ChangeGate();
    // 订阅必须在 success/error/timeout/cancel/interruption 全路径释放。
    try (HarnessThreadChangeSource.Subscription subscription =
        changeSource.subscribe(threadId, gate::version)) {
      ChangeGate.State since = gate.snapshot();
      boolean first = true;
      while (true) {
        if (!continueWaiting.getAsBoolean()) {
          stop(runtime, threadId);
          throw new IllegalStateException("one-shot caller is no longer active");
        }
        ChangeGate.State current = gate.snapshot();
        boolean versionWake = first || current.version() != since.version();
        since = current;
        first = false;
        if (versionWake) {
          // 终态优先：订阅后的首次权威读取与 version/resync 唤醒后都先检查 terminal，再判 timeout。
          ThreadSnapshot snapshot = runtime.getThreadSnapshot(threadId);
          String result = terminalText(snapshot);
          if (result != null) {
            return result;
          }
        }
        // 相对 elapsed 计时：remaining 非负、无绝对 deadline，避免 nanoTime + timeout 溢出。
        long remaining = timeoutNanos - (System.nanoTime() - startedAt);
        if (remaining <= 0L) {
          stop(runtime, threadId);
          throw new IllegalStateException("one-shot Harness execution timed out after " + timeout);
        }
        long sliceNanos = Math.min(remaining, CALLER_CHECK_NANOS);
        try {
          gate.awaitChange(since, sliceNanos);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(
              "one-shot observation thread was interrupted", interrupted);
        }
      }
    }
  }

  public void stop(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    stop(requireRuntime(), threadId);
  }

  private static String terminalText(ThreadSnapshot snapshot) {
    if (!snapshot.queuedCommands().isEmpty()
        || snapshot.model() != null
        || !snapshot.toolSiblings().isEmpty()
        || !(snapshot.entryPath().head().payload() instanceof TurnEndPayload end)
        || end.continueModel()) {
      return null;
    }
    if (end.outcome() != TurnEndOutcome.COMPLETED) {
      throw new IllegalStateException(
          "one-shot Harness execution ended with " + end.outcome() + ": " + lastFailure(snapshot));
    }
    String text = lastAssistantText(snapshot);
    if (text == null || text.isBlank()) {
      throw new IllegalStateException("one-shot Harness execution returned no Assistant text");
    }
    return text;
  }

  private static String lastAssistantText(ThreadSnapshot snapshot) {
    String result = null;
    for (Entry entry : snapshot.entryPath().entries()) {
      if (entry.payload() instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.ASSISTANT) {
        List<String> parts = new ArrayList<>();
        for (AgentMessageContent content : message.message().contents()) {
          if (content instanceof TextMessageContent text) {
            parts.add(text.text());
          }
        }
        String candidate = String.join("", parts).trim();
        if (!candidate.isBlank()) {
          result = candidate;
        }
      }
    }
    return result;
  }

  private static String lastFailure(ThreadSnapshot snapshot) {
    String result = snapshot.entryPath().head().payload().type().name();
    for (Entry entry : snapshot.entryPath().entries()) {
      if (entry.payload() instanceof AssistantErrorPayload error) {
        result = error.error().message();
      } else if (entry.payload() instanceof AssistantAbortedPayload aborted) {
        String text =
            aborted.message().contents().stream()
                .filter(TextMessageContent.class::isInstance)
                .map(TextMessageContent.class::cast)
                .map(TextMessageContent::text)
                .reduce("", String::concat)
                .trim();
        if (!text.isBlank()) {
          result = text;
        }
      }
    }
    return result;
  }

  private static void stop(HarnessRuntime runtime, UUID threadId) {
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        ThreadSnapshot snapshot = runtime.getThreadSnapshot(threadId);
        runtime.stop(new StopCommand(threadId, UUID.randomUUID(), snapshot.thread().version()));
        return;
      } catch (HarnessRuntimeConflictException stale) {
        // version 前进时重读后重试。
      } catch (HarnessRuntimeNotFoundException notFound) {
        return;
      }
    }
  }

  private HarnessRuntime requireRuntime() {
    return Objects.requireNonNull(
        runtimes.getIfAvailable(), "HarnessRuntime is required for one-shot execution");
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
