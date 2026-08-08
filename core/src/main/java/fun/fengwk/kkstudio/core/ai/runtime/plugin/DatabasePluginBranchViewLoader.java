package fun.fengwk.kkstudio.core.ai.runtime.plugin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.plugin.BranchView;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.util.Objects;

/** 使用 Harness append-only Entry path 构造冻结插件 branch view。 */
@Component
@ConditionalOnBean(HarnessStore.class)
public class DatabasePluginBranchViewLoader implements PluginBranchViewLoader {

  private final HarnessStore store;

  public DatabasePluginBranchViewLoader(HarnessStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  @Override
  public BranchView load(long assistantEntryId) {
    if (assistantEntryId <= 0) {
      throw new IllegalArgumentException("assistantEntryId must be positive");
    }
    return store.transaction(tx -> new BranchView(tx.loadEntryPath(assistantEntryId)));
  }
}
