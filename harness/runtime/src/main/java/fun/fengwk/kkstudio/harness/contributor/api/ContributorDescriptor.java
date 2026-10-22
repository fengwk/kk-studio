package fun.fengwk.kkstudio.harness.contributor.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Contributor 的静态元数据：canonical id、展示名、版本与依赖声明。 */
public record ContributorDescriptor(
    ContributorId id, String name, String version, Set<ContributorId> requires) {

  public ContributorDescriptor {
    id = Objects.requireNonNull(id, "id");
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (version == null || version.isBlank()) {
      throw new IllegalArgumentException("version must not be blank");
    }
    Objects.requireNonNull(requires, "requires");
    List<ContributorId> sortedRequires = new ArrayList<>(requires.size());
    for (ContributorId required : requires) {
      sortedRequires.add(Objects.requireNonNull(required, "requires[]"));
    }
    if (sortedRequires.contains(id)) {
      throw new IllegalArgumentException("contributor cannot require itself: " + id);
    }
    sortedRequires.sort(Comparator.comparing(ContributorId::value));
    requires = Collections.unmodifiableSet(new LinkedHashSet<>(sortedRequires));
  }
}
