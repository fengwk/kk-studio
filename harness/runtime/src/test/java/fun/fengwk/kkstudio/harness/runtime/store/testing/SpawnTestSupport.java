package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.SpawnRequest;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;

import java.util.List;
import java.util.UUID;

/**
 * spawn 相关测试的共用构造器：把「源命令 + 票据」的紧凑写法翻译成 {@link SpawnRequest}，让测试聚焦票据语义而不是请求样板。
 *
 * <p>{@link Ticket} 只携带票据准入所需的事实；交付方式决定其余派生：{@code NOTIFY_PARENT} 计入额度并携带父 head，{@code
 * SETTLE_OWNER_TURN} 与 {@code RECEIPT_ONLY} 都不计额度。
 */
public final class SpawnTestSupport {

  public static final String HASH = "a".repeat(64);

  private SpawnTestSupport() {}

  /** 票据事实。 */
  public record Ticket(
      UUID invocationId,
      UUID parentThreadId,
      UUID parentHeadEntryId,
      String requestHash,
      String agent,
      Integer maxTurns,
      int maxDepth,
      int maxConcurrentChildren,
      int maxConcurrentThreads,
      JoinDelivery delivery) {

    /** task 票据：有父则通知父，无父则只留回执。 */
    public static Ticket task(
        UUID invocationId,
        UUID parent,
        UUID head,
        String hash,
        String agent,
        Integer maxTurns,
        int maxDepth,
        int maxChildren,
        int maxThreads) {
      return new Ticket(
          invocationId,
          parent,
          head,
          hash,
          agent,
          maxTurns,
          maxDepth,
          maxChildren,
          maxThreads,
          parent == null ? JoinDelivery.RECEIPT_ONLY : JoinDelivery.NOTIFY_PARENT);
    }

    /** 压缩类票据：结算父 Thread 的开放 Turn，不计额度。 */
    public static Ticket settle(UUID invocationId, UUID parent, String hash, String agent) {
      return new Ticket(
          invocationId,
          parent,
          null,
          hash,
          agent,
          null,
          Integer.MAX_VALUE,
          Integer.MAX_VALUE,
          Integer.MAX_VALUE,
          JoinDelivery.SETTLE_OWNER_TURN);
    }

    UUID parent() {
      return parentThreadId;
    }
  }

  /** 新建子 Thread 的来源。 */
  public record Child(UUID session, UUID thread, UUID parent, List<NewThreadCommand> commands) {}

  public static Child child(int session, int thread, UUID parent) {
    return new Child(
        TestIds.id(session),
        TestIds.id(thread),
        parent,
        List.of(
            new NewThreadCommand(
                new UserMessageCommandPayload(AgentMessage.user("prompt")),
                TestIds.id(session + 1000))));
  }

  private static BranchSettings defaultSettings() {
    return new BranchSettings("agent", ModelSelection.model("model", "v1"), null);
  }

  public static AcceptedCommands acceptAndJoin(
      HarnessRuntime runtime, Child source, Ticket ticket, AcceptancePreflight preflight) {
    return runtime.spawn(
        request(
            source.thread(),
            source.session(),
            defaultSettings(),
            source.commands(),
            preflight,
            null,
            false,
            ticket));
  }

  /** 根 ticket（NewRootSession）或续用既有 Thread（Thread target）。 */
  public static AcceptedCommands acceptAndJoin(
      HarnessRuntime runtime,
      AcceptCommandsCommand source,
      Ticket ticket,
      AcceptancePreflight preflight) {
    return switch (source.target()) {
      case AcceptCommandsTarget.NewRootSession root -> runtime.spawn(
          request(
              root.threadId(),
              root.sessionId(),
              root.rootSettings(),
              source.commands(),
              preflight,
              null,
              root.yoloEnabled(),
              ticket));
      case AcceptCommandsTarget.Thread thread -> runtime.spawn(
          request(
              thread.threadId(),
              null,
              defaultSettings(),
              source.commands(),
              preflight,
              new SpawnRequest.Continuation(
                  thread.expectedHeadEntryId(), thread.expectedNextCommandSequence()),
              false,
              ticket));
      default -> throw new IllegalArgumentException("unsupported source " + source.target());
    };
  }

  public static SpawnRequest request(
      UUID threadId,
      UUID sessionId,
      BranchSettings settings,
      List<NewThreadCommand> commands,
      AcceptancePreflight preflight,
      SpawnRequest.Continuation continuation,
      boolean yoloEnabled,
      Ticket ticket) {
    boolean notify = ticket.delivery() == JoinDelivery.NOTIFY_PARENT;
    BranchSettings agentSettings =
        new BranchSettings(
            ticket.agent(), settings.model(), settings.environmentName(), settings.goal());
    return new SpawnRequest(
        threadId,
        sessionId,
        agentSettings,
        commands,
        preflight,
        ticket.parentThreadId(),
        notify ? ticket.parentHeadEntryId() : null,
        continuation,
        yoloEnabled,
        ThreadConstraints.NONE,
        ticket.delivery() == JoinDelivery.SETTLE_OWNER_TURN
            ? SpawnTrigger.RUNTIME
            : notify ? SpawnTrigger.MODEL_TOOL : SpawnTrigger.CONTROLLER,
        ticket.delivery(),
        notify,
        new SpawnRequest.Budget(
            ticket.maxTurns(),
            ticket.maxDepth(),
            ticket.maxConcurrentChildren(),
            ticket.maxConcurrentThreads()),
        ticket.invocationId(),
        ticket.requestHash());
  }
}
