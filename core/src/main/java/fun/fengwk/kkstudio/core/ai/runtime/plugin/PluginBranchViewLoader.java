package fun.fengwk.kkstudio.core.ai.runtime.plugin;

import fun.fengwk.kkstudio.harness.plugin.BranchView;

import java.util.UUID;

/** 按冻结 Assistant Entry 加载插件只读 branch view。 */
public interface PluginBranchViewLoader {

  BranchView load(UUID assistantEntryId);
}
