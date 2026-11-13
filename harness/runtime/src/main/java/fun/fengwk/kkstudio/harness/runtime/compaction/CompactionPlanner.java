package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;

import java.util.Objects;
import java.util.Optional;

/** Selects a closed-turn cut for a compaction branch. */
public final class CompactionPlanner {

  private final CompactionConfig config;

  public CompactionPlanner(CompactionConfig config) {
    this.config = Objects.requireNonNull(config, "config");
  }

  /** Returns empty when the path contains no complete prefix that can be summarized. */
  public Optional<CompactionPreparation> prepare(
      EntryPath path, CompactionTrigger trigger, long contextWindow) {
    Objects.requireNonNull(trigger, "trigger");
    return new CompactionCutPlanner(config)
        .plan(path, contextWindow)
        .map(cut -> new CompactionPreparation(trigger, cut.entryId()));
  }
}
