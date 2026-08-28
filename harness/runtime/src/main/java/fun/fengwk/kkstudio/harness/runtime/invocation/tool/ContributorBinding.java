package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Contributor 的冻结 provenance 与 branch state 访问声明。
 *
 * <p>{@code contributorId + localName} 在重启后精确恢复贡献 owner；state accesses 用于在执行前拒绝同一 Assistant
 * 内必然读取陈旧快照的 sibling 组合。
 */
public record ContributorBinding(
    String contributorId, String localName, List<ContributorStateAccess> stateAccesses) {

  public ContributorBinding {
    contributorId = ToolBindingIdentifiers.requireCanonical(contributorId, "contributorId");
    localName = ToolBindingIdentifiers.requireCanonical(localName, "localName");
    stateAccesses = List.copyOf(Objects.requireNonNull(stateAccesses, "stateAccesses"));
    Set<String> customTypes = new HashSet<>();
    for (ContributorStateAccess access : stateAccesses) {
      Objects.requireNonNull(access, "stateAccesses[]");
      if (!customTypes.add(access.customType())) {
        throw new IllegalArgumentException(
            "duplicate contributor state access customType: " + access.customType());
      }
    }
  }
}
