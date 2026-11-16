package fun.fengwk.kkstudio.harness.api.tool;

import fun.fengwk.kkstudio.harness.api.history.CanonicalIdentifiers;

/**
 * Contributor 的冻结 provenance。
 *
 * <p>{@code contributorId + localName} 在重启后精确恢复贡献 owner；两者都是 canonical 小写 dotted/dashed 标识符。
 */
public record ContributorBinding(String contributorId, String localName) {

  public ContributorBinding {
    contributorId = CanonicalIdentifiers.requireCanonicalIdentifier(contributorId, "contributorId");
    localName = CanonicalIdentifiers.requireCanonicalIdentifier(localName, "localName");
  }
}
