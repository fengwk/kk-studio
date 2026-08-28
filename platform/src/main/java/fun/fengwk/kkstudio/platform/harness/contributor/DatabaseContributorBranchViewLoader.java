package fun.fengwk.kkstudio.platform.harness.contributor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.util.Objects;
import java.util.UUID;

/** 使用 Harness append-only Entry path 构造冻结 contributor branch view。 */
@Component
@ConditionalOnBean(HarnessStore.class)
public class DatabaseContributorBranchViewLoader implements ContributorBranchViewLoader {

  private final HarnessStore store;

  public DatabaseContributorBranchViewLoader(HarnessStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  @Override
  public BranchView load(UUID assistantEntryId) {
    Objects.requireNonNull(assistantEntryId, "assistantEntryId");
    return store.transaction(tx -> new BranchView(tx.loadEntryPath(assistantEntryId)));
  }
}
