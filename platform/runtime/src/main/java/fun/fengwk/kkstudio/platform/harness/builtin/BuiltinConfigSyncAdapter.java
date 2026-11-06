package fun.fengwk.kkstudio.platform.harness.builtin;

import fun.fengwk.kkstudio.common.json.StrictJson;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModelDefaultVariantResolver;
import fun.fengwk.kkstudio.share.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.catalog.AgentModelKind;
import fun.fengwk.kkstudio.share.catalog.HarnessExecutionSelectionDTO;

/** 配置同步对 Builtin 模型引用与导入后 variant 的判定。 */
public final class BuiltinConfigSyncAdapter {

  private BuiltinConfigSyncAdapter() {}

  public static ModelSelection model(ExecutionSelection execution) {
    if (execution == null || !BuiltinExecution.DRIVER.equals(execution.driver())) {
      return null;
    }
    return BuiltinExecution.model(execution);
  }

  /** 仅在调用方已通过 AgentDefinitionService.validateImport 后使用。 */
  public static ModelSelection model(HarnessExecutionSelectionDTO execution) {
    if (execution == null || !BuiltinExecution.DRIVER.equals(execution.getDriver())) {
      return null;
    }
    return model(
        new ExecutionSelection(
            execution.getDriver(),
            StrictJson.standard().write(execution.getSettings(), "execution.settings")));
  }

  public static VariantIssue variantIssue(
      ModelSelection selection, AgentModelKind kind, AgentModelConfigDTO config) {
    if (selection.variant() == null) {
      return null;
    }
    if (kind == AgentModelKind.GROUP) {
      return VariantIssue.GROUP_VARIANT;
    }
    try {
      AgentModelDefaultVariantResolver.resolveConfiguredVariant(
          selection.modelName(), selection.variant(), config);
      return null;
    } catch (IllegalArgumentException error) {
      return VariantIssue.UNKNOWN_VARIANT;
    }
  }

  public enum VariantIssue {
    GROUP_VARIANT,
    UNKNOWN_VARIANT
  }
}
