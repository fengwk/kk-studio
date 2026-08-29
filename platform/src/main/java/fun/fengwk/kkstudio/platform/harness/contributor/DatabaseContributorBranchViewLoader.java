package fun.fengwk.kkstudio.platform.harness.contributor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.util.Objects;
import java.util.UUID;

/** 使用 Harness append-only Entry path 构造作用域受限的冻结 contributor branch view。 */
@Component
@ConditionalOnBean(HarnessStore.class)
public class DatabaseContributorBranchViewLoader implements ContributorBranchViewLoader {

  private final HarnessStore store;

  public DatabaseContributorBranchViewLoader(HarnessStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  @Override
  public BranchView load(UUID assistantEntryId, String contributorId) {
    Objects.requireNonNull(assistantEntryId, "assistantEntryId");
    Objects.requireNonNull(contributorId, "contributorId");
    return store.transaction(
        tx -> new ScopedBranchView(tx.loadEntryPath(assistantEntryId).entries(), contributorId));
  }
}
