package fun.fengwk.kkstudio.platform.catalog.tool;

import fun.fengwk.kkstudio.harness.contributor.api.ContributionId;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.StateDeclaration;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccess;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolPluginSource;

import java.util.Map;
import java.util.Objects;

/**
 * 冻结 binding 与某一份已冻结运行时目录的严格一致性校验：epoch、完整定义、贡献身份、requirements 与插件来源任一不一致都拒绝。
 *
 * <p>校验只读取传入的冻结目录与来源表，绝不按当前同名工具兜底，也不按当前插件状态重解释历史 binding。执行边界与历史重建共用同一套规则， 因此不存在"某些入口更宽松"的第二种语义。
 */
final class FrozenToolBindingVerifier {

  private FrozenToolBindingVerifier() {}

  /**
   * 在给定冻结目录上取回 binding 声明的贡献。
   *
   * @throws ToolBindingMismatchException 任一冻结事实与 binding 不一致时
   */
  static ToolContribution require(
      long catalogEpoch,
      RuntimeToolCatalog catalog,
      Map<ContributorId, ToolPluginSource> pluginSourcesByContributor,
      ToolBinding binding) {
    Objects.requireNonNull(binding, "binding");
    if (binding.catalogEpoch() != catalogEpoch) {
      throw new ToolBindingMismatchException(
          ToolBindingMismatchException.Reason.DEFINITION,
          "binding catalogEpoch does not match the acquired catalog epoch");
    }
    String toolName = binding.descriptor().name();
    ToolContribution contribution = catalog.findTool(toolName).orElse(null);
    if (contribution == null) {
      throw new ToolBindingMismatchException(
          ToolBindingMismatchException.Reason.NOT_FOUND,
          "Frozen tool definition " + toolName + " is not registered");
    }
    if (!contribution.definition().equals(binding.definition())) {
      throw new ToolBindingMismatchException(
          ToolBindingMismatchException.Reason.DEFINITION,
          "Frozen tool definition " + toolName + " does not match its catalog definition");
    }
    ContributorBinding frozenContributor = binding.contributor();
    ContributionId expectedContributionId =
        new ContributionId(
            new ContributorId(frozenContributor.contributorId()), frozenContributor.localName());
    if (!contribution.id().equals(expectedContributionId)) {
      throw new ToolBindingMismatchException(
          ToolBindingMismatchException.Reason.CONTRIBUTOR,
          "Frozen contributor binding "
              + frozenContributor.contributorId()
              + "/"
              + frozenContributor.localName()
              + " does not match the catalog contribution");
    }
    ToolRequirements requirements = contribution.requirements();
    if (binding.environmentSupport() != requirements.environmentSupport()) {
      throw new ToolBindingMismatchException(
          ToolBindingMismatchException.Reason.REQUIREMENTS,
          "Frozen tool environment requirement does not match catalog requirements: " + toolName);
    }
    if (requirements.requiredEnvironmentId() != null
        && binding.environmentId() != null
        && !requirements.requiredEnvironmentId().equals(binding.environmentId())) {
      throw new ToolBindingMismatchException(
          ToolBindingMismatchException.Reason.REQUIREMENTS,
          "Frozen tool bound environment "
              + binding.environmentId()
              + " does not match catalog required environment: "
              + requirements.requiredEnvironmentId());
    }
    if (!stateAccessesMatch(frozenContributor, requirements)) {
      throw new ToolBindingMismatchException(
          ToolBindingMismatchException.Reason.REQUIREMENTS,
          "Tool definition " + toolName + " no longer matches its frozen state access declaration");
    }
    ToolPluginSource expectedSource =
        pluginSourcesByContributor.get(contribution.id().contributorId());
    if (!Objects.equals(expectedSource, binding.pluginSource())) {
      throw new ToolBindingMismatchException(
          ToolBindingMismatchException.Reason.SOURCE,
          "Frozen plugin source does not match the acquired catalog epoch: " + toolName);
    }
    return contribution;
  }

  private static boolean stateAccessesMatch(
      ContributorBinding binding, ToolRequirements requirements) {
    if (binding.stateAccesses().size() != requirements.stateAccesses().size()) {
      return false;
    }
    for (int i = 0; i < binding.stateAccesses().size(); i++) {
      ContributorStateAccess frozen = binding.stateAccesses().get(i);
      StateDeclaration current = requirements.stateAccesses().get(i);
      if (!frozen.customType().equals(current.customType())
          || !frozen.mode().name().equals(current.mode().name())) {
        return false;
      }
    }
    return true;
  }
}
