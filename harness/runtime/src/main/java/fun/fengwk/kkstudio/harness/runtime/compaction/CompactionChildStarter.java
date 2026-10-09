package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.runtime.Names;
import fun.fengwk.kkstudio.harness.runtime.ThreadCreationRequestHash;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.join.JoinPurpose;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloPolicy;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 压缩子 Thread 创建与第一条摘要命令派发：自动压缩与手动压缩复用。
 *
 * <p>在父执行树事务锁内原子创建独立的 Session、Root Entry、Child Thread、第一条摘要 Command 与 COMPACTION Join， 并请求子 Thread
 * 的 THREAD Work。子 Thread 的 YOLO 策略跟随执行树根，且不参与全局 subagent 配额。
 */
public final class CompactionChildStarter {

  private CompactionChildStarter() {}

  public record CompactionChild(
      UUID childSessionId, UUID childThreadId, UUID joinInvocationId, CompactionStart frozenStart) {
    public CompactionChild {
      Objects.requireNonNull(childSessionId, "childSessionId");
      Objects.requireNonNull(childThreadId, "childThreadId");
      Objects.requireNonNull(joinInvocationId, "joinInvocationId");
      Objects.requireNonNull(frozenStart, "frozenStart");
    }
  }

  public static CompactionChild start(
      HarnessStore.Transaction tx,
      ThreadState parentThread,
      EntryPath parentPath,
      TurnResolver.CompactionResolved resolved,
      CompactionPreparation preparation,
      Instant mutationNow) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(parentThread, "parentThread");
    Objects.requireNonNull(parentPath, "parentPath");
    Objects.requireNonNull(resolved, "resolved");
    Objects.requireNonNull(preparation, "preparation");
    Objects.requireNonNull(mutationNow, "mutationNow");

    UUID childSessionId = tx.nextId();
    UUID childThreadId = tx.nextId();
    UUID rootEntryId = tx.nextId();
    UUID joinInvocationId = tx.nextId();

    BranchSettings childSettings = resolved.childSettings();
    Session session =
        new Session(childSessionId, Names.defaultSessionName(childSessionId), mutationNow);
    tx.insertSession(session);

    tx.insertEntry(
        new Entry(rootEntryId, childSessionId, null, new RootPayload(childSettings), mutationNow));

    List<UUID> ancestors = tx.findAncestorChain(parentThread.id());
    UUID rootThreadId =
        ancestors.isEmpty() ? parentThread.id() : ancestors.get(ancestors.size() - 1);
    ThreadYoloPolicy yoloPolicy = ThreadYoloPolicy.follow(rootThreadId);

    String prompt;
    if (preparation.phase() == CompactionPhase.TURN_PREFIX) {
      prompt = CompactionPrompts.turnPrefixUserPrompt(preparation.messagesToSummarize());
    } else {
      prompt =
          CompactionPrompts.summaryUserPrompt(
              preparation.messagesToSummarize(), preparation.previousSummary());
    }
    CustomMessageCommandPayload payload =
        new CustomMessageCommandPayload(AgentMessage.user(prompt));
    String requestHash = ThreadCommandPayloadJsonCodec.requestHash(payload);

    NewThreadCommand newThreadCommand =
        new NewThreadCommand(payload, joinInvocationId, requestHash);
    String creationRequestHash =
        ThreadCreationRequestHash.forNewSession(
            childSessionId,
            childThreadId,
            childSettings,
            parentThread.id(),
            yoloPolicy,
            List.of(newThreadCommand));

    ThreadState child =
        new ThreadState(
            childThreadId,
            childSessionId,
            parentThread.id(),
            rootEntryId,
            creationRequestHash,
            Names.rootThreadName(),
            yoloPolicy,
            ThreadExecutionControl.RUNNABLE,
            0L,
            1L,
            0L,
            mutationNow,
            mutationNow);
    tx.insertThread(child);

    ThreadCommand command =
        new ThreadCommand(
            childThreadId,
            1L,
            payload,
            joinInvocationId,
            requestHash,
            null,
            null,
            null,
            mutationNow);
    tx.insertCommands(List.of(command));
    tx.updateThread(child.reserveCommandSequences(1, mutationNow));

    ThreadJoin join =
        new ThreadJoin(
            joinInvocationId,
            requestHash,
            parentThread.id(),
            childThreadId,
            1L,
            childSettings.agentName(),
            null,
            0L,
            null,
            null,
            null,
            mutationNow,
            mutationNow,
            JoinPurpose.COMPACTION,
            null);
    tx.insertJoin(join);

    tx.requestWork(new WorkTarget(WorkTargetType.THREAD, childThreadId), mutationNow);

    CompactionStart frozenStart =
        new CompactionStart(
            preparation.phase(),
            preparation.trigger(),
            resolved.executionModel(),
            resolved.outputBudget(),
            preparation.cutEntryId(),
            preparation.turnPrefixStartEntryId(),
            preparation.historyCompactionEntryId(),
            childThreadId,
            joinInvocationId);

    return new CompactionChild(childSessionId, childThreadId, joinInvocationId, frozenStart);
  }
}
