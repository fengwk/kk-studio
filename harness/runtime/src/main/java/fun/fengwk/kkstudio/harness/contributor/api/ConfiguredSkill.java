package fun.fengwk.kkstudio.harness.contributor.api;

/**
 * Skill package facts frozen with the internal {@code load_skill} tool binding for this Agent turn.
 */
public record ConfiguredSkill(String name, String packageName, String version) {

  public ConfiguredSkill {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (packageName == null || packageName.isBlank()) {
      throw new IllegalArgumentException("packageName must not be blank");
    }
    if (version == null || version.isBlank()) {
      throw new IllegalArgumentException("version must not be blank");
    }
  }
}
