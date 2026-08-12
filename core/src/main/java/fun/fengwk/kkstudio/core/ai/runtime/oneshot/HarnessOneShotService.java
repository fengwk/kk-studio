package fun.fengwk.kkstudio.core.ai.runtime.oneshot;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.runtime.task.AgentBranchSettingsMaterializer;
import fun.fengwk.kkstudio.core.ai.runtime.task.SubagentConfig;
import fun.fengwk.kkstudio.harness.runtime.CreateThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.CreatedThread;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime.NewCommandPreflight;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
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
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandBatch;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** 面向内部编排的一次性 Harness 调用器。它只创建无工具 root Thread、原子提交 SYSTEM/USER 消息、观察终态并提取最后一条 Assistant 文本。 */
@Component
public final class HarnessOneShotService {

  private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(100);

  private final ObjectProvider<HarnessRuntime> runtimes;
  private final AgentBranchSettingsMaterializer settingsMaterializer;
  private final SubagentConfig subagentConfig;
  private final Duration pollInterval;

  @Autowired
  public HarnessOneShotService(
      ObjectProvider<HarnessRuntime> runtimes,
      AgentBranchSettingsMaterializer settingsMaterializer,
      SubagentConfig subagentConfig) {
    this(runtimes, settingsMaterializer, subagentConfig, DEFAULT_POLL_INTERVAL);
  }

  HarnessOneShotService(
      ObjectProvider<HarnessRuntime> runtimes,
      AgentBranchSettingsMaterializer settingsMaterializer,
      SubagentConfig subagentConfig,
      Duration pollInterval) {
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    this.settingsMaterializer =
        Objects.requireNonNull(settingsMaterializer, "settingsMaterializer");
    this.subagentConfig = Objects.requireNonNull(subagentConfig, "subagentConfig");
    this.pollInterval = requirePositive(pollInterval, "pollInterval");
  }

  public UUID submit(
      String agentName,
      EnvironmentName environmentName,
      String systemMessage,
      AgentMessage userMessage) {
    return submit(
        agentName, environmentName, systemMessage, userMessage, NewCommandPreflight.IDENTITY);
  }

  /**
   * 带 media preflight 的一次性提交：在入队事务内（幂等重放检查之后）把 USER 消息中按 manifest 顺序的占位内容物化为 durable 内容（如 H3 的全局存储
   * RESOURCE）；preflight 必须保持 clientCommandId/requestHash 不变。CUSTOM_MESSAGE 请求 hash 只基于 durable
   * 形态计算，因此 USER 消息在提交时不得携带瞬时 media/attachment 内容。
   */
  public UUID submit(
      String agentName,
      EnvironmentName environmentName,
      String systemMessage,
      AgentMessage userMessage,
      NewCommandPreflight preflight) {
    Objects.requireNonNull(userMessage, "userMessage");
    Objects.requireNonNull(preflight, "preflight");
    if (userMessage.role() != AgentMessageRole.USER) {
      throw new IllegalArgumentException("one-shot userMessage must use USER role");
    }
    HarnessRuntime runtime = requireRuntime();
    var settings =
        settingsMaterializer
            .materialize(agentName, environmentName, 1, subagentConfig)
            .withActiveTools(List.of());
    CreatedThread created = runtime.createThread(new CreateThreadCommand(settings, false));
    runtime.enqueueCommands(
        new ThreadCommandBatch(
            created.thread().id(),
            created.thread().headEntryId(),
            created.thread().nextCommandSequence(),
            List.of(
                new NewThreadCommand(
                    new CustomMessageCommandPayload(AgentMessage.system(systemMessage)),
                    UUID.randomUUID(),
                    ThreadCommandPayloadJsonCodec.requestHash(
                        new CustomMessageCommandPayload(AgentMessage.system(systemMessage)))),
                new NewThreadCommand(
                    new CustomMessageCommandPayload(userMessage),
                    UUID.randomUUID(),
                    ThreadCommandPayloadJsonCodec.requestHash(
                        new CustomMessageCommandPayload(userMessage))))),
        preflight);
    return created.thread().id();
  }

  public String await(UUID threadId, Duration timeout, BooleanSupplier continueWaiting) {
    Objects.requireNonNull(threadId, "threadId");
    Duration boundedTimeout = requirePositive(timeout, "timeout");
    Objects.requireNonNull(continueWaiting, "continueWaiting");
    HarnessRuntime runtime = requireRuntime();
    long deadline = System.nanoTime() + boundedTimeout.toNanos();
    while (true) {
      if (!continueWaiting.getAsBoolean()) {
        stop(runtime, threadId);
        throw new IllegalStateException("one-shot caller is no longer active");
      }
      ThreadSnapshot snapshot = runtime.getThreadSnapshot(threadId);
      String result = terminalText(snapshot);
      if (result != null) {
        return result;
      }
      if (System.nanoTime() - deadline >= 0L) {
        stop(runtime, threadId);
        throw new IllegalStateException("one-shot Harness execution timed out after " + timeout);
      }
      sleep();
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
        runtime.stop(new StopCommand(threadId, UUID.randomUUID(), snapshot.thread().revision()));
        return;
      } catch (HarnessRuntimeConflictException stale) {
        // revision 前进时重读后重试。
      } catch (HarnessRuntimeNotFoundException notFound) {
        return;
      }
    }
  }

  private HarnessRuntime requireRuntime() {
    return Objects.requireNonNull(
        runtimes.getIfAvailable(), "HarnessRuntime is required for one-shot execution");
  }

  private void sleep() {
    try {
      Thread.sleep(pollInterval);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("one-shot observation thread was interrupted", interrupted);
    }
  }

  private static Duration requirePositive(Duration value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(field + " must be positive");
    }
    return value;
  }
}
