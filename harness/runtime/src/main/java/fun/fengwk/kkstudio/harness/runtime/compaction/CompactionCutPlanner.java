package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.api.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.ModelAttemptFailurePayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Selects a closed-turn boundary that leaves the newest model-call turn in the main thread. */
public final class CompactionCutPlanner {

  private final CompactionConfig config;

  public CompactionCutPlanner(CompactionConfig config) {
    this.config = Objects.requireNonNull(config, "config");
  }

  /** Returns the oldest usable boundary required by both the keep-recent limit and latest call. */
  public Optional<Cut> plan(EntryPath path, long contextWindow) {
    Objects.requireNonNull(path, "path");
    if (contextWindow <= 0) {
      throw new IllegalArgumentException("contextWindow must be positive");
    }
    List<Entry> entries = path.entries();
    if (path.openTurnStart().isPresent()) {
      throw new IllegalArgumentException("compaction cut planning requires a closed turn path");
    }

    int priorCutIndex = latestCompleteCutIndex(path, entries);
    List<Turn> turns = closedTurns(entries, priorCutIndex);
    boolean[] visible = TokenEstimator.visibilityMask(entries);
    int latestCall = latestModelCallTurn(turns);
    if (latestCall < 0) {
      return Optional.empty();
    }

    Entry latestBoundary =
        latestCall == 0
            ? entries.get(priorCutIndex)
            : entries.get(turns.get(latestCall - 1).endIndex());
    int keepBoundaryIndex =
        keepRecentBoundary(
            entries, visible, turns, priorCutIndex, config.effectiveKeep(contextWindow));
    Entry boundary = latestBoundary;
    if (keepBoundaryIndex < indexOf(entries, latestBoundary.id())) {
      boundary = entries.get(keepBoundaryIndex);
    }
    int boundaryIndex = indexOf(entries, boundary.id());
    if (boundaryIndex <= priorCutIndex) {
      return Optional.empty();
    }
    return Optional.of(new Cut(boundary.id(), boundaryIndex));
  }

  private static int latestCompleteCutIndex(EntryPath path, List<Entry> entries) {
    return CompactionTurns.latestComplete(path)
        .map(
            turn -> {
              for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).id().equals(turn.freezing().cutEntryId())) {
                  return i;
                }
              }
              throw new IllegalStateException("complete compaction cut is not on the current path");
            })
        .orElse(0);
  }

  private static List<Turn> closedTurns(List<Entry> entries, int afterIndex) {
    List<Turn> turns = new ArrayList<>();
    int startIndex = -1;
    TurnStartReason reason = null;
    boolean modelCall = false;
    for (int i = afterIndex + 1; i < entries.size(); i++) {
      Entry entry = entries.get(i);
      if (entry.payload() instanceof TurnStartPayload start) {
        startIndex = i;
        reason = start.reason();
        modelCall = false;
      } else if (entry.payload() instanceof TurnEndPayload && startIndex >= 0) {
        turns.add(new Turn(startIndex, i, reason, modelCall));
        startIndex = -1;
        reason = null;
        modelCall = false;
      } else if (startIndex >= 0) {
        modelCall |=
            entry.payload() instanceof ModelAttemptFailurePayload
                || entry.payload() instanceof AssistantAbortedPayload
                || entry.payload() instanceof AssistantErrorPayload
                || (entry.payload() instanceof MessagePayload message
                    && message.message().role() == AgentMessageRole.ASSISTANT);
      }
    }
    return turns;
  }

  private static int latestModelCallTurn(List<Turn> turns) {
    for (int turnIndex = turns.size() - 1; turnIndex >= 0; turnIndex--) {
      Turn turn = turns.get(turnIndex);
      if (!turn.modelCall()
          || (turn.reason() != TurnStartReason.INPUT
              && turn.reason() != TurnStartReason.CONTINUATION)) {
        continue;
      }
      return turnIndex;
    }
    return -1;
  }

  private static int keepRecentBoundary(
      List<Entry> entries,
      boolean[] visible,
      List<Turn> turns,
      int lowerBoundIndex,
      long keepTokens) {
    long accumulated = 0L;
    for (int turnIndex = turns.size() - 1; turnIndex >= 0; turnIndex--) {
      Turn turn = turns.get(turnIndex);
      for (int i = turn.startIndex(); i <= turn.endIndex(); i++) {
        if (visible[i]) {
          accumulated = Math.addExact(accumulated, TokenEstimator.estimateTokens(entries.get(i)));
        }
      }
      if (accumulated > keepTokens) {
        return turnIndex == 0 ? lowerBoundIndex : turns.get(turnIndex - 1).endIndex();
      }
    }
    return lowerBoundIndex;
  }

  private static int indexOf(List<Entry> entries, UUID id) {
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).id().equals(id)) {
        return i;
      }
    }
    throw new IllegalStateException("cut boundary is not on the entry path: " + id);
  }

  public record Cut(UUID entryId, int pathIndex) {
    public Cut {
      Objects.requireNonNull(entryId, "entryId");
      if (pathIndex < 0) {
        throw new IllegalArgumentException("pathIndex must not be negative");
      }
    }
  }

  private record Turn(int startIndex, int endIndex, TurnStartReason reason, boolean modelCall) {}
}
