package fun.fengwk.kkstudio.harness.runtime;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandBatch;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongConsumer;

/**
 * Root Harness command/control/query plane: the synchronous public entry point of the durable Agent
 * Runtime.
 *
 * <p>Every method runs exactly one {@link HarnessStore} transaction with the canonical lock order
 * Thread -&gt; Commands -&gt; ModelInvocation -&gt; ToolInvocation siblings -&gt; Work, so command
 * enqueue, head relocation, Tool approval and snapshot never observe a mixed durable state. All
 * business rejections are typed {@link HarnessRuntimeConflictException} / {@link
 * HarnessRuntimeNotFoundException}; broken persistence invariants (wrong ownership, mixed sibling
 * attachment, non-contiguous ordinals) stay {@link IllegalStateException}. Mutations of an existing
 * Thread read their timestamp after the relevant durable locks, so a lock wait never lets a stale
 * pre-lock instant regress {@code updatedAt}; Stop additionally clamps its timestamp to the newest
 * locked durable fact to tolerate local clock rollback and cross-node skew.
 *
 * <p>This slice implements {@link #createThread}, {@link #enqueueCommands}, {@link #moveHead},
 * {@link #stop}, {@link #decideToolApproval} and {@link #getThreadSnapshot}.
 */
@Slf4j
public final class HarnessRuntime {

  private static final LongConsumer NO_OP_CANCELLER = ignored -> {};

  private final HarnessStore store;
  private final Clock clock;
  private final StopControl stopControl;
  private final LongConsumer modelExecutionCanceller;
  private final LongConsumer toolExecutionCanceller;

  /**
   * Creates the complete Runtime facade, including process-local execution cancellation after a
   * durable Stop commit.
   */
  public HarnessRuntime(
      HarnessStore store, Clock clock, ModelProcessor modelProcessor, ToolProcessor toolProcessor) {
    this(
        store,
        clock,
        modelExecutionCanceller(modelProcessor),
        toolExecutionCanceller(toolProcessor));
  }

  /**
   * Creates a control-only Runtime that does not host local Model/Tool executions.
   *
   * <p>Stop remains correct through durable terminal state and Work fencing; only the optional
   * same-process best-effort cancellation is absent.
   */
  public HarnessRuntime(HarnessStore store, Clock clock) {
    this(store, clock, NO_OP_CANCELLER, NO_OP_CANCELLER);
  }

  /** Internal constructor shared by concrete Processor wiring and local execution adapters. */
  HarnessRuntime(
      HarnessStore store,
      Clock clock,
      LongConsumer modelExecutionCanceller,
      LongConsumer toolExecutionCanceller) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.stopControl = new StopControl(store, clock);
    this.modelExecutionCanceller =
        Objects.requireNonNull(modelExecutionCanceller, "modelExecutionCanceller");
    this.toolExecutionCanceller =
        Objects.requireNonNull(toolExecutionCanceller, "toolExecutionCanceller");
  }

  /**
   * Atomically creates Session + ROOT (initial complete {@code BranchSettings}) + Thread (head
   * ROOT, the supplied YOLO policy, {@code nextCommandSequence=1}, {@code revision=0}, one shared
   * timestamp) in a single transaction, allocating globally unique ids via {@code nextId()}.
   *
   * <p>No Work row is created. The current 7-table model has no {@code createRequestId}, so this
   * API is deliberately non-idempotent: every call creates a brand-new Session/Thread pair.
   */
  public CreatedThread createThread(CreateThreadCommand command) {
    Objects.requireNonNull(command, "command");
    return store.transaction(
        tx -> {
          Instant now = clock.instant();
          long sessionId = tx.nextId();
          long rootEntryId = tx.nextId();
          long threadId = tx.nextId();
          Session session = new Session(sessionId, command.title(), now);
          Entry rootEntry =
              new Entry(
                  rootEntryId, sessionId, null, new RootPayload(command.branchSettings()), now);
          tx.insertSession(session);
          tx.insertEntry(rootEntry);
          ThreadState thread =
              new ThreadState(threadId, rootEntryId, command.yoloEnabled(), 1, 0, now, now);
          tx.insertThread(thread);
          return new CreatedThread(session, rootEntry, thread);
        });
  }

  /**
   * Atomically enqueues one ordered command set.
   *
   * <p>Idempotency lookup of every {@code clientCommandId} happens before any head/sequence/live
   * check. When every id already exists this is an <em>ordered command-set replay</em> (not an
   * exact HTTP batch replay — there is deliberately no batch identity): the batch is accepted iff
   * each stored payload equals the request payload and the stored sequences are contiguous in
   * request order ({@code seq[i] == seq[0] + i}); the expected head/next sequence and the
   * QUEUED/APPLIED/CANCELLED lifecycle are ignored and the existing rows are returned unchanged. A
   * partially existing id set conflicts with PARTIAL_COMMAND_REPLAY, a reused id with a different
   * payload with COMMAND_ID_REUSED, and a matching set in a non-contiguous order with
   * COMMAND_REPLAY_ORDER_MISMATCH; missing commands are never filled in.
   *
   * <p>For an entirely new batch the exact expected head + next command sequence cursor is required
   * (STALE_COMMAND_CURSOR otherwise), then N continuous sequences are reserved in one atomic step
   * (revision +1), all commands are inserted as QUEUED, THREAD Work is requested and everything
   * commits atomically. A new batch containing SET_ENVIRONMENT additionally requires a truly
   * quiescent pre-state (no queued USER/CUSTOM message, the shared classifier result
   * IDLE_OR_HISTORICAL, and no THREAD Work row at all — a present row, leased or not, fences a
   * speculative Resolver/runnable mailbox). Exact replay bypasses that admission check.
   */
  public List<ThreadCommand> enqueueCommands(ThreadCommandBatch batch) {
    Objects.requireNonNull(batch, "batch");
    return store.transaction(
        tx -> {
          ThreadState thread =
              tx.lockThread(batch.threadId())
                  .orElseThrow(
                      () ->
                          new HarnessRuntimeNotFoundException(
                              "thread " + batch.threadId() + " does not exist"));
          List<Optional<ThreadCommand>> existing = new ArrayList<>(batch.commands().size());
          for (NewThreadCommand request : batch.commands()) {
            existing.add(tx.findCommandByClientId(batch.threadId(), request.clientCommandId()));
          }
          long present = existing.stream().filter(Optional::isPresent).count();
          if (present > 0 && present < existing.size()) {
            throw conflict(
                HarnessRuntimeConflictException.Reason.PARTIAL_COMMAND_REPLAY,
                "batch on thread "
                    + thread.id()
                    + " replays only "
                    + present
                    + " of "
                    + existing.size()
                    + " commands");
          }
          if (present == existing.size()) {
            return replayExistingBatch(batch, existing);
          }
          return enqueueNewBatch(tx, batch, thread, clock.instant());
        });
  }

  /**
   * Synchronously relocates the Thread head cursor.
   *
   * <p>When the current head already equals the target the current Thread is returned untouched
   * (PUT-style no-op replay) before any revision/target validation and without a revision bump.
   * Otherwise {@code expectedRevision} must match (STALE_REVISION), the target must exist and stay
   * in the current head session (MOVE_TARGET_CROSS_SESSION), no queued command and no live/pending
   * Model/Tool context may exist (THREAD_NOT_QUIESCENT / TERMINAL_APPLY_PENDING; moving away from a
   * current CONTINUATION_DUE obligation is allowed), and the target itself must not be a {@code
   * continueModel=true} TURN_END (MOVE_TARGET_HAS_CONTINUATION_OBLIGATION). The head advances
   * exactly once preserving YOLO and the latest next sequence (revision +1), and the THREAD Work
   * row is force-deleted last to fence a speculative Resolver; no Work is requested. The effective
   * Environment is derived from the target branch, never copied.
   */
  public ThreadState moveHead(MoveHeadCommand command) {
    Objects.requireNonNull(command, "command");
    return store.transaction(
        tx -> {
          ThreadState thread =
              tx.lockThread(command.threadId())
                  .orElseThrow(
                      () ->
                          new HarnessRuntimeNotFoundException(
                              "thread " + command.threadId() + " does not exist"));
          if (thread.headEntryId() == command.targetEntryId()) {
            return thread;
          }
          if (thread.revision() != command.expectedRevision()) {
            throw conflict(
                HarnessRuntimeConflictException.Reason.STALE_REVISION,
                "thread "
                    + thread.id()
                    + " revision "
                    + thread.revision()
                    + " does not match expected "
                    + command.expectedRevision());
          }
          EntryPath headPath = tx.loadEntryPath(thread.headEntryId());
          Entry target =
              tx.findEntry(command.targetEntryId())
                  .orElseThrow(
                      () ->
                          new HarnessRuntimeNotFoundException(
                              "entry " + command.targetEntryId() + " does not exist"));
          EntryPath targetPath = tx.loadEntryPath(command.targetEntryId());
          if (targetPath.root().sessionId() != headPath.root().sessionId()) {
            throw conflict(
                HarnessRuntimeConflictException.Reason.MOVE_TARGET_CROSS_SESSION,
                "target entry "
                    + command.targetEntryId()
                    + " is in session "
                    + targetPath.root().sessionId()
                    + " while thread "
                    + thread.id()
                    + " is in session "
                    + headPath.root().sessionId());
          }
          if (!tx.loadQueuedCommands(thread.id()).isEmpty()) {
            throw conflict(
                HarnessRuntimeConflictException.Reason.THREAD_NOT_QUIESCENT,
                "thread " + thread.id() + " still has queued commands");
          }
          LockedThreadContext locked = ThreadContextLock.load(tx, thread, headPath);
          if (locked.context() instanceof ThreadContext.ModelActive
              || locked.context() instanceof ThreadContext.ToolActive) {
            throw conflict(
                HarnessRuntimeConflictException.Reason.THREAD_NOT_QUIESCENT,
                "thread "
                    + thread.id()
                    + " has a live model/tool context: "
                    + contextName(locked.context()));
          }
          if (locked.context() instanceof ThreadContext.ModelTerminalPending
              || locked.context() instanceof ThreadContext.ToolTerminalPending) {
            throw conflict(
                HarnessRuntimeConflictException.Reason.TERMINAL_APPLY_PENDING,
                "thread "
                    + thread.id()
                    + " has an unattached terminal result: "
                    + contextName(locked.context()));
          }
          if (target.payload() instanceof TurnEndPayload end && end.continueModel()) {
            throw conflict(
                HarnessRuntimeConflictException.Reason.MOVE_TARGET_HAS_CONTINUATION_OBLIGATION,
                "target entry " + target.id() + " is a continueModel=true TURN_END");
          }
          ThreadState moved =
              thread.advanceHead(command.targetEntryId(), thread.yoloEnabled(), clock.instant());
          tx.updateThread(moved);
          tx.deleteWork(new WorkTarget(WorkTargetType.THREAD, thread.id()));
          return moved;
        });
  }

  /**
   * Decides a required Tool approval in one short transaction.
   *
   * <p>Lock order: Thread -&gt; Model -&gt; Tool siblings -&gt; Work. Unlocked reads only discover
   * the immutable ids/ownership and choose the stable branch — approval decisions are serialized by
   * the Thread lock and immutable once decided. An already decided approval is validated as an
   * exact replay after locking the owning Model and the target Tool in canonical order via {@link
   * ToolApproval#decide} with a fresh {@code now}, and returns that locked current ToolInvocation
   * (original {@code decidedAt} preserved, no current branch/status requirement, no revision bump,
   * no Work request); any mismatch is APPROVAL_DECISION_MISMATCH, a vanished or identity-changed
   * entity is an invariant ISE, and a locked row owned by another Thread stays
   * APPROVAL_NOT_APPLICABLE. An undecided approval must be the WAITING_APPROVAL invocation inside
   * the locked current TOOL_ACTIVE context; the transition basis is the locked sibling, never the
   * pre-lock snapshot, and the target is not locked individually before the siblings. ALLOWED
   * resumes it as READY and requests TOOL Work, DENIED terminates it as FAILED and requests THREAD
   * Work, and the Thread revision is touched exactly once either way.
   */
  public ToolInvocation decideToolApproval(ToolApprovalCommand command) {
    Objects.requireNonNull(command, "command");
    return store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(command.threadId()).orElse(null);
          if (thread == null) {
            throw approvalNotApplicable("thread " + command.threadId() + " does not exist");
          }
          ToolInvocation probe = tx.findToolInvocation(command.toolInvocationId()).orElse(null);
          if (probe == null) {
            throw approvalNotApplicable(
                "tool invocation " + command.toolInvocationId() + " does not exist");
          }
          ModelInvocation probeModel =
              tx.findModelInvocation(probe.modelInvocationId()).orElse(null);
          if (probeModel == null || probeModel.threadId() != command.threadId()) {
            throw approvalNotApplicable(
                "tool invocation "
                    + probe.id()
                    + " does not belong to thread "
                    + command.threadId());
          }
          ToolApproval probeApproval = probe.approval();
          if (probeApproval == null || !probeApproval.required()) {
            throw approvalNotApplicable(
                "tool invocation " + probe.id() + " has no required approval");
          }
          if (probeApproval.decision() != null) {
            return replayDecidedApproval(tx, probe, command);
          }
          return decideUndecidedApproval(tx, thread, probe, command);
        });
  }

  /**
   * Atomically stops the current live Turn, cancels queued Commands, or replays an earlier
   * thread-owned Stop.
   *
   * <p>The durable transaction is owned by {@link StopControl}. Only after it commits does this
   * method best-effort cancel matching process-local Model/Tool executions; a local cancellation
   * failure is logged and cannot change the committed result.
   */
  public StopResult stop(StopCommand command) {
    StopControl.Commit commit = stopControl.stop(command);
    if (commit.modelExecutionId() != null) {
      cancelLocalExecution(modelExecutionCanceller, "Model", commit.modelExecutionId());
    }
    for (long toolExecutionId : commit.toolExecutionIds()) {
      cancelLocalExecution(toolExecutionCanceller, "Tool", toolExecutionId);
    }
    return commit.result();
  }

  /**
   * Reads one consistent Thread snapshot in a single short transaction (Thread -&gt; queued
   * Commands -&gt; applicable Model -&gt; Tool siblings), never mixing states across transactions.
   *
   * <p>Returns the ThreadState, the current root-to-head {@link EntryPath}, the immutable queued
   * Commands, and only the classifier-applicable ModelInvocation / Tool siblings:
   * IDLE_OR_HISTORICAL and CONTINUATION_DUE expose neither, Model contexts expose the Model only,
   * Tool contexts expose the Model plus all siblings. No derived status is persisted or returned.
   */
  public ThreadSnapshot getThreadSnapshot(long threadId) {
    return store.transaction(
        tx -> {
          ThreadState thread =
              tx.lockThread(threadId)
                  .orElseThrow(
                      () ->
                          new HarnessRuntimeNotFoundException(
                              "thread " + threadId + " does not exist"));
          List<ThreadCommand> queued = tx.loadQueuedCommands(threadId);
          LockedThreadContext locked = ThreadContextLock.load(tx, thread);
          return switch (locked.context()) {
            case ThreadContext.IdleOrHistorical ignored -> new ThreadSnapshot(
                thread, locked.path(), queued, null, List.of());
            case ThreadContext.ContinuationDue ignored -> new ThreadSnapshot(
                thread, locked.path(), queued, null, List.of());
            case ThreadContext.ModelActive active -> new ThreadSnapshot(
                thread, locked.path(), queued, active.model(), List.of());
            case ThreadContext.ModelTerminalPending pending -> new ThreadSnapshot(
                thread, locked.path(), queued, pending.model(), List.of());
            case ThreadContext.ToolActive active -> new ThreadSnapshot(
                thread, locked.path(), queued, active.model(), active.siblings());
            case ThreadContext.ToolTerminalPending pending -> new ThreadSnapshot(
                thread, locked.path(), queued, pending.model(), pending.siblings());
          };
        });
  }

  /** Ordered command-set replay: payload equality first, then request-order sequence contiguity. */
  private static List<ThreadCommand> replayExistingBatch(
      ThreadCommandBatch batch, List<Optional<ThreadCommand>> found) {
    List<ThreadCommand> ordered = new ArrayList<>(found.size());
    for (int i = 0; i < found.size(); i++) {
      ThreadCommand existing = found.get(i).orElseThrow();
      NewThreadCommand request = batch.commands().get(i);
      if (!existing.payload().equals(request.payload())) {
        throw conflict(
            HarnessRuntimeConflictException.Reason.COMMAND_ID_REUSED,
            "clientCommandId "
                + request.clientCommandId()
                + " is reused with a different payload on thread "
                + batch.threadId());
      }
      ordered.add(existing);
    }
    long firstSequence = ordered.get(0).sequence();
    for (int i = 0; i < ordered.size(); i++) {
      if (ordered.get(i).sequence() != Math.addExact(firstSequence, (long) i)) {
        throw conflict(
            HarnessRuntimeConflictException.Reason.COMMAND_REPLAY_ORDER_MISMATCH,
            "existing commands on thread "
                + batch.threadId()
                + " are not contiguous in the request order");
      }
    }
    return List.copyOf(ordered);
  }

  /**
   * Fresh-batch enqueue: CAS cursor, optional SET_ENVIRONMENT admission, insert + reserve + Work.
   */
  private static List<ThreadCommand> enqueueNewBatch(
      HarnessStore.Transaction tx, ThreadCommandBatch batch, ThreadState thread, Instant now) {
    if (thread.headEntryId() != batch.expectedHeadEntryId()
        || thread.nextCommandSequence() != batch.expectedNextCommandSequence()) {
      throw conflict(
          HarnessRuntimeConflictException.Reason.STALE_COMMAND_CURSOR,
          "thread "
              + thread.id()
              + " head/next command sequence does not match the batch expectation");
    }
    if (containsSetEnvironment(batch)) {
      requireQuiescentForSetEnvironment(tx, thread);
    }
    List<ThreadCommand> inserted = new ArrayList<>(batch.commands().size());
    long nextSequence = thread.nextCommandSequence();
    for (int i = 0; i < batch.commands().size(); i++) {
      NewThreadCommand request = batch.commands().get(i);
      inserted.add(
          new ThreadCommand(
              tx.nextId(),
              thread.id(),
              nextSequence + i,
              request.payload(),
              request.clientCommandId(),
              null,
              null,
              now));
    }
    tx.insertCommands(inserted);
    tx.updateThread(thread.reserveCommandSequences(inserted.size(), now));
    tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()), now);
    return List.copyOf(inserted);
  }

  private static boolean containsSetEnvironment(ThreadCommandBatch batch) {
    for (NewThreadCommand command : batch.commands()) {
      if (command.payload().type() == ThreadCommandType.SET_ENVIRONMENT) {
        return true;
      }
    }
    return false;
  }

  /**
   * SET_ENVIRONMENT admission over the pre-state: no queued USER/CUSTOM command, the shared locked
   * classifier result must be IDLE_OR_HISTORICAL, and the THREAD Work row must be absent entirely
   * (not merely unleased) so a speculative Resolver/runnable mailbox is fenced. Lock order: Thread
   * -&gt; existing Commands -&gt; applicable Model -&gt; Tool siblings -&gt; Work.
   */
  private static void requireQuiescentForSetEnvironment(
      HarnessStore.Transaction tx, ThreadState thread) {
    for (ThreadCommand queued : tx.loadQueuedCommands(thread.id())) {
      if (queued.type().isMessage()) {
        throw conflict(
            HarnessRuntimeConflictException.Reason.THREAD_NOT_QUIESCENT,
            "SET_ENVIRONMENT on thread " + thread.id() + " requires no queued USER/CUSTOM message");
      }
    }
    LockedThreadContext locked = ThreadContextLock.load(tx, thread);
    if (!(locked.context() instanceof ThreadContext.IdleOrHistorical)) {
      throw conflict(
          HarnessRuntimeConflictException.Reason.THREAD_NOT_QUIESCENT,
          "SET_ENVIRONMENT on thread "
              + thread.id()
              + " requires an idle thread, got "
              + contextName(locked.context()));
    }
    if (tx.findWork(new WorkTarget(WorkTargetType.THREAD, thread.id())).isPresent()) {
      throw conflict(
          HarnessRuntimeConflictException.Reason.THREAD_NOT_QUIESCENT,
          "SET_ENVIRONMENT on thread " + thread.id() + " requires no THREAD work row");
    }
  }

  /**
   * Already decided approval: lock the owning Model then the target Tool in canonical Thread -&gt;
   * Model -&gt; Tool order and validate exact replay against the locked current approval, returning
   * that locked current ToolInvocation (a replay after ToolProcessor status changes returns the
   * current row). A previously found entity that vanished or whose identity changed is an invariant
   * ISE; a locked row owned by another Thread stays APPROVAL_NOT_APPLICABLE.
   */
  private ToolInvocation replayDecidedApproval(
      HarnessStore.Transaction tx, ToolInvocation probe, ToolApprovalCommand command) {
    ModelInvocation model =
        tx.lockModelInvocation(probe.modelInvocationId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "model "
                            + probe.modelInvocationId()
                            + " could not be locked for approval replay of tool "
                            + probe.id()));
    if (model.threadId() != command.threadId()) {
      throw approvalNotApplicable(
          "tool invocation " + probe.id() + " does not belong to thread " + command.threadId());
    }
    ToolInvocation tool =
        tx.lockToolInvocation(probe.id())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "tool invocation "
                            + probe.id()
                            + " could not be locked for approval replay"));
    if (tool.modelInvocationId() != probe.modelInvocationId()) {
      throw new IllegalStateException(
          "tool invocation " + tool.id() + " changed its model attachment during approval replay");
    }
    ToolApproval approval = tool.approval();
    if (approval == null || !approval.required() || approval.decision() == null) {
      throw new IllegalStateException(
          "tool invocation " + tool.id() + " lost its required decided approval during replay");
    }
    try {
      Instant now = clock.instant();
      ToolApproval replayed =
          approval.decide(
              command.decision(), command.decisionId(), command.actor(), command.reason(), now);
      if (replayed != approval) {
        throw new IllegalStateException("a decided approval must replay exactly");
      }
    } catch (IllegalArgumentException error) {
      throw conflict(
          HarnessRuntimeConflictException.Reason.APPROVAL_DECISION_MISMATCH,
          "approval of tool invocation " + tool.id() + " does not replay the stored decision");
    }
    return tool;
  }

  /**
   * Undecided approval: the target must be the WAITING_APPROVAL invocation inside the locked
   * current TOOL_ACTIVE context; the transition basis is the ToolInvocation from the locked
   * siblings, never the pre-lock snapshot, and the target is never locked individually before the
   * siblings (ordinal sibling order preserved). The Thread revision is touched exactly once and the
   * matching Work target requested.
   */
  private ToolInvocation decideUndecidedApproval(
      HarnessStore.Transaction tx,
      ThreadState thread,
      ToolInvocation probe,
      ToolApprovalCommand command) {
    LockedThreadContext locked = ThreadContextLock.load(tx, thread);
    if (!(locked.context() instanceof ThreadContext.ToolActive active)) {
      throw approvalNotApplicable(
          "tool invocation " + probe.id() + " is not in the current tool context");
    }
    ToolInvocation tool = null;
    for (ToolInvocation sibling : active.siblings()) {
      if (sibling.id() == probe.id()) {
        tool = sibling;
        break;
      }
    }
    if (tool == null) {
      throw approvalNotApplicable(
          "tool invocation " + probe.id() + " is not in the current tool context");
    }
    if (tool.status() != ToolInvocationStatus.WAITING_APPROVAL || tool.resultEntryId() != null) {
      throw approvalNotApplicable("tool invocation " + tool.id() + " is not waiting for approval");
    }
    ToolApproval approval = tool.approval();
    if (approval == null || !approval.required() || approval.decision() != null) {
      throw new IllegalStateException(
          "tool invocation " + tool.id() + " changed its approval state before being decided");
    }
    Instant now = clock.instant();
    ToolInvocation updated =
        tool.decideApproval(
            command.decision(), command.decisionId(), command.actor(), command.reason(), now, now);
    tx.updateToolInvocations(List.of(updated));
    tx.updateThread(thread.touchRevision(now));
    WorkTarget wake =
        command.decision() == ToolApprovalDecision.ALLOWED
            ? new WorkTarget(WorkTargetType.TOOL, tool.id())
            : new WorkTarget(WorkTargetType.THREAD, thread.id());
    tx.requestWork(wake, now);
    return updated;
  }

  private static LongConsumer modelExecutionCanceller(ModelProcessor processor) {
    Objects.requireNonNull(processor, "modelProcessor");
    return processor::cancel;
  }

  private static LongConsumer toolExecutionCanceller(ToolProcessor processor) {
    Objects.requireNonNull(processor, "toolProcessor");
    return processor::cancel;
  }

  private static void cancelLocalExecution(
      LongConsumer canceller, String executionType, long invocationId) {
    try {
      canceller.accept(invocationId);
    } catch (RuntimeException failure) {
      log.warn(
          "cannot cancel process-local {} execution {} after durable Stop commit",
          executionType,
          invocationId,
          failure);
    }
  }

  private static HarnessRuntimeConflictException approvalNotApplicable(String message) {
    return conflict(HarnessRuntimeConflictException.Reason.APPROVAL_NOT_APPLICABLE, message);
  }

  private static HarnessRuntimeConflictException conflict(
      HarnessRuntimeConflictException.Reason reason, String message) {
    return new HarnessRuntimeConflictException(reason, message);
  }

  private static String contextName(ThreadContext context) {
    return context.getClass().getSimpleName();
  }
}
