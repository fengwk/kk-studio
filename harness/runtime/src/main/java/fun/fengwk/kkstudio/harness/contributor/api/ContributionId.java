package fun.fengwk.kkstudio.harness.contributor.api;

import java.util.Objects;

/**
 * Contributor 内贡献的 owner-qualified 稳定身份：{@code (contributorId, localName)}。
 *
 * <p>{@code localName} 是 canonical 小写 dotted/dashed 标识符，只在所属 contributor 内唯一；不同 contributor 可以使用相同
 * localName。
 */
public record ContributionId(ContributorId contributorId, String localName)
    implements Comparable<ContributionId> {

  public ContributionId {
    contributorId = Objects.requireNonNull(contributorId, "contributorId");
    Identifiers.requireCanonical(localName, "contributionId.localName");
  }

  @Override
  public String toString() {
    return contributorId + ":" + localName;
  }

  @Override
  public int compareTo(ContributionId other) {
    Objects.requireNonNull(other, "other");
    int contributorComparison = contributorId.value().compareTo(other.contributorId.value());
    return contributorComparison != 0
        ? contributorComparison
        : localName.compareTo(other.localName);
  }
}
