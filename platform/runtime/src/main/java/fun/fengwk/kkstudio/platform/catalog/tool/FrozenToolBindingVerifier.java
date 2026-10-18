package fun.fengwk.kkstudio.platform.catalog.tool;

import fun.fengwk.kkstudio.harness.contributor.api.ContributionId;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.StateDeclaration;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccess;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;

import java.util.Objects;

/** 冻结 binding 与当前运行时目录的一致性校验：工具不存在，或完整定义、贡献身份、requirements 任一变化都拒绝，绝不按新定义执行旧参数。 */
final class FrozenToolBindingVerifier {

  private FrozenToolBindingVerifier() {}

  /**
   * 在给定冻结目录上取回 binding 声明的贡献。
   *
   * @throws ToolBindingMismatchException 任一冻结事实与 binding 不一致时
   */
  static ToolContribution require(RuntimeToolCatalog catalog, ToolBinding binding) {
    Objects.requireNonNull(binding, "binding");
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
