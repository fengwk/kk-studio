package fun.fengwk.kkstudio.harness.api.tool;

/**
 * Contributor 的冻结 provenance。
 *
 * <p>{@code contributorId + localName} 在重启后精确恢复贡献 owner。
 */
public record ContributorBinding(String contributorId, String localName) {

  public ContributorBinding {
    contributorId = ToolBindingIdentifiers.requireCanonical(contributorId, "contributorId");
    localName = ToolBindingIdentifiers.requireCanonical(localName, "localName");
  }
}
