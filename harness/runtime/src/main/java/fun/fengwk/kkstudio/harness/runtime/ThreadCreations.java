package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.HarnessConflictReason;
import fun.fengwk.kkstudio.harness.api.Names;
import fun.fengwk.kkstudio.harness.api.SpawnRequest;
import fun.fengwk.kkstudio.harness.api.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.ForkPayload;
import fun.fengwk.kkstudio.harness.api.history.RootPayload;
import fun.fengwk.kkstudio.harness.api.history.SettingsPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.Session;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.spi.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.store.UuidOrder;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Creates execution roots, ticketed same-session branches, and idle history branches/forks.
 *
 * <p>Each method runs inside the caller's transaction. The caller is responsible for acquiring the
 * required locks before invoking transaction-scoped creation methods.
 */
final class ThreadCreations {

  private ThreadCreations() {}

  /** Creates a Session, ROOT Entry, Thread, and its initial command batch. */
  static AcceptedCommands createSessionWithThread(
      HarnessStore.Transaction tx,
      UUID sessionId,
      UUID threadId,
      UUID parentThreadId,
      UUID rootThreadId,
      BranchSettings rootSettings,
      boolean yoloEnabled,
      ThreadKind kind,
      ThreadConstraints constraints,
      String creationRequestHash,
      List<NewThreadCommand> commands,
      AcceptancePreflight preflight) {
    Instant now = tx.now();
    Session session = new Session(sessionId, initialSessionName(commands, sessionId), now);
    tx.sessions().insert(session);
    UUID rootEntryId = tx.nextId();
    tx.entries()
        .insert(new Entry(rootEntryId, sessionId, null, new RootPayload(rootSettings), now));
    ThreadState thread =
        new ThreadState(
            threadId,
            sessionId,
            parentThreadId,
            rootEntryId,
            creationRequestHash,
            Names.rootThreadName(),
            rootThreadId,
            yoloEnabled,
            kind,
            constraints,
            ThreadExecutionControl.RUNNABLE,
            0L,
            1L,
            0,
            now,
            now);
    tx.threads().insert(thread);
    return AcceptCommandsControl.acceptNewCommandsOnThread(
        tx, thread, commands, preflight, now, rootSettings);
  }

  /** Creates a ticketed COMPACTION branch sharing its owner's Session and closed-cut prefix. */
  static AcceptedCommands createSameSessionBranchWithThread(
      HarnessStore.Transaction tx,
      SpawnRequest request,
      String creationRequestHash,
      AcceptancePreflight preflight,
      boolean userRequestedCompaction) {
    SpawnRequest.SameSessionBranch branch = request.sameSessionBranch();
    ThreadState parent =
        tx.threads()
            .find(request.parentThreadId())
            .orElseThrow(() -> new IllegalArgumentException("join parent does not exist"));
    if (parent.kind() == ThreadKind.COMPACTION) {
      throw new IllegalArgumentException("COMPACTION threads cannot spawn children");
    }
    if (parent.executionControl().isStopped()
        && !(userRequestedCompaction && AcceptCommandsValidation.resumeConverged(tx, parent))) {
      throw new IllegalArgumentException("join parent is stopped");
    }
    if (!parent.sessionId().equals(request.sessionId())) {
      throw new IllegalArgumentException("same-session branch must reuse its owner's Session");
    }
    if (tx.threads().listChildren(parent.id()).stream()
        .filter(child -> child.kind() == ThreadKind.COMPACTION)
        .anyMatch(
            child ->
                tx.joins().loadIncomplete(child.id()).stream()
                    .anyMatch(join -> join.delivery() == JoinDelivery.SETTLE_OWNER_TURN))) {
      throw new IllegalArgumentException("a compaction branch is already active for this Thread");
    }
    Entry cutEntry =
        tx.entries()
            .find(branch.startEntryId())
            .orElseThrow(() -> new IllegalArgumentException("compaction cut entry does not exist"));
    if (!cutEntry.sessionId().equals(request.sessionId())) {
      throw new IllegalArgumentException("compaction cut entry belongs to a different Session");
    }
    AcceptCommandsValidation.requireLegalForkBoundary(cutEntry);
    boolean cutOnParentPath =
        tx.entries().loadPath(parent.headEntryId()).entries().stream()
            .anyMatch(entry -> entry.id().equals(branch.startEntryId()));
    if (!cutOnParentPath) {
      throw new IllegalArgumentException("compaction cut entry is not on the owner's current path");
    }
    Instant now = tx.now();
    UUID forkEntryId = tx.nextId();
    tx.entries()
        .insert(
            new Entry(
                forkEntryId,
                request.sessionId(),
                branch.startEntryId(),
                ForkPayload.branch(branch.startEntryId()),
                now));
    UUID settingsEntryId = tx.nextId();
    tx.entries()
        .insert(
            new Entry(
                settingsEntryId,
                request.sessionId(),
                forkEntryId,
                new SettingsPayload(request.settings(), request.threadId()),
                now));
    ThreadState thread =
        new ThreadState(
            request.threadId(),
            request.sessionId(),
            parent.id(),
            settingsEntryId,
            creationRequestHash,
            "Compaction",
            parent.rootThreadId(),
            false,
            ThreadKind.COMPACTION,
            request.constraints().merge(parent.constraints()),
            ThreadExecutionControl.RUNNABLE,
            0L,
            1L,
            0,
            now,
            now);
    tx.threads().insert(thread);
    return AcceptCommandsControl.acceptNewCommandsOnThread(
        tx, thread, request.commands(), preflight, now, request.settings());
  }

  static ThreadState createIdleBranch(
      HarnessStore.Transaction tx,
      UUID sourceThreadId,
      UUID turnEndEntryId,
      UUID threadId,
      String threadName) {
    String name = Names.normalize(threadName);
    String hash =
        ThreadCreationRequestHash.forIdleRoot(sourceThreadId, turnEndEntryId, null, threadId, name);
    ThreadState replay = lockAndReplayIdleRoot(tx, sourceThreadId, threadId, null, hash);
    if (replay != null) {
      return replay;
    }
    ThreadState source = lockIdleSource(tx, sourceThreadId);
    Entry cut = requireTurnEndCut(tx, source, turnEndEntryId);
    if (tx.threads().listBySession(source.sessionId()).stream()
        .anyMatch(t -> t.parentThreadId() == null && t.name().equals(name))) {
      throw DomainException.conflict(
          HarnessConflictReason.THREAD_NAME_CONFLICT.name(), "thread name already exists");
    }
    return insertIdleRoot(tx, source, source.sessionId(), threadId, cut.id(), name, hash, tx.now());
  }

  static ThreadState createIdleFork(
      HarnessStore.Transaction tx,
      UUID sourceThreadId,
      UUID turnEndEntryId,
      UUID sessionId,
      UUID threadId,
      String sessionName,
      BiConsumer<UUID, List<Entry>> retainCopiedResources) {
    String name = Names.normalize(sessionName);
    String hash =
        ThreadCreationRequestHash.forIdleRoot(
            sourceThreadId,
            turnEndEntryId,
            Objects.requireNonNull(sessionId, "sessionId"),
            threadId,
            name);
    ThreadState replay = lockAndReplayIdleRoot(tx, sourceThreadId, threadId, sessionId, hash);
    if (replay != null) {
      return replay;
    }
    if (tx.sessions().find(sessionId).isPresent()) {
      throw idleRootIdReused();
    }
    ThreadState source = lockIdleSource(tx, sourceThreadId);
    Entry cut = requireTurnEndCut(tx, source, turnEndEntryId);
    Instant now = tx.now();
    if (!tx.sessions().insertIfAbsent(new Session(sessionId, name, now))) {
      throw idleRootIdReused();
    }
    tx.sessions().lockForUpdate(sessionId).orElseThrow();
    Entry root =
        new Entry(
            tx.nextId(),
            sessionId,
            null,
            new RootPayload(tx.entries().loadBranchSettings(cut.id())),
            now);
    tx.entries().insert(root);
    List<Entry> copied =
        SessionForkCopy.copyEffectiveContext(
            tx, sessionId, root.id(), tx.entries().loadPath(cut.id()), now);
    ThreadState fork =
        insertIdleRoot(
            tx,
            source,
            sessionId,
            threadId,
            copied.getLast().id(),
            Names.rootThreadName(),
            hash,
            now);
    retainCopiedResources.accept(sessionId, copied);
    return fork;
  }

  private static ThreadState insertIdleRoot(
      HarnessStore.Transaction tx,
      ThreadState source,
      UUID sessionId,
      UUID threadId,
      UUID headId,
      String name,
      String hash,
      Instant now) {
    ThreadState thread =
        new ThreadState(
            threadId,
            sessionId,
            null,
            headId,
            hash,
            name,
            threadId,
            source.yoloEnabled(),
            ThreadKind.USER,
            ThreadConstraints.NONE,
            ThreadExecutionControl.RUNNABLE,
            0L,
            1L,
            0,
            now,
            now);
    tx.threads().insert(thread);
    return thread;
  }

  private static ThreadState lockAndReplayIdleRoot(
      HarnessStore.Transaction tx, UUID sourceId, UUID targetId, UUID sessionId, String hash) {
    List.of(sourceId, targetId).stream()
        .distinct()
        .sorted(UuidOrder.COMPARATOR)
        .forEach(tx.threads()::lockTree);
    ThreadState existing = tx.threads().find(targetId).orElse(null);
    if (existing == null) {
      return null;
    }
    if (!existing.creationRequestHash().equals(hash)
        || (sessionId != null && !sessionId.equals(existing.sessionId()))) {
      throw idleRootIdReused();
    }
    tx.sessions().lockForKeyShare(existing.sessionId()).orElseThrow();
    return tx.threads().lock(targetId).orElseThrow();
  }

  private static DomainException idleRootIdReused() {
    return DomainException.conflict(
        HarnessConflictReason.THREAD_ID_REUSED.name(), "thread or fork session id already exists");
  }

  static void validateBranchCut(HarnessStore.Transaction tx, UUID sourceId, UUID cutId) {
    tx.threads().lockTree(sourceId);
    ThreadState source = lockIdleSource(tx, sourceId);
    requireTurnEndCut(tx, source, cutId);
  }

  private static ThreadState lockIdleSource(HarnessStore.Transaction tx, UUID sourceId) {
    ThreadState source =
        tx.threads()
            .find(sourceId)
            .filter(t -> t.parentThreadId() == null && t.kind() == ThreadKind.USER)
            .orElseThrow(
                () -> DomainException.notFound("thread", "public source thread does not exist"));
    tx.sessions().lockForUpdate(source.sessionId()).orElseThrow();
    return source;
  }

  private static Entry requireTurnEndCut(
      HarnessStore.Transaction tx, ThreadState source, UUID cutId) {
    return tx.entries()
        .find(cutId)
        .filter(
            cut ->
                cut.sessionId().equals(source.sessionId())
                    && cut.payload() instanceof TurnEndPayload)
        .filter(cut -> tx.entries().isAncestor(source.headEntryId(), cut.id()))
        .orElseThrow(
            () ->
                DomainException.conflict(
                    HarnessConflictReason.INVALID_TURN_END.name(),
                    "entry is not a turn end on the source path"));
  }

  /** Derives the initial Session name from the trailing user-like command's first nonblank text. */
  static String initialSessionName(List<NewThreadCommand> commands, UUID sessionId) {
    NewThreadCommand trailing = commands.get(commands.size() - 1);
    AgentMessage message = userMessage(trailing);
    for (AgentMessageContent content : message.contents()) {
      if (content instanceof TextMessageContent text) {
        String name = Names.sessionNameFromUserText(text.text());
        if (name != null) {
          return name;
        }
      }
    }
    return Names.defaultSessionName(sessionId);
  }

  private static AgentMessage userMessage(NewThreadCommand command) {
    if (command.payload() instanceof UserMessageCommandPayload payload) {
      return payload.message();
    }
    return ((CustomMessageCommandPayload) command.payload()).message();
  }
}
