package fun.fengwk.kkstudio.harness.runtime.skill;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Loads full SKILL.md body from a named Environment without exposing local paths.
 *
 * <p>Thin runtime port over Core's Environment skill loader so CONTROL tools do not depend on Host
 * lifecycle beans directly.
 */
public interface SkillBodyLoader {

  CompletableFuture<SkillBodyLoadResult> load(
      String environmentName, String skillName, Duration timeout);

  /** Bounded skill body load outcome. */
  sealed interface SkillBodyLoadResult
      permits SkillBodyLoadResult.Loaded, SkillBodyLoadResult.Failed {
    String skillName();

    record Loaded(String skillName, String content) implements SkillBodyLoadResult {
      public Loaded {
        skillName = requireNonBlank(skillName, "skillName");
        content = Objects.requireNonNull(content, "content");
      }
    }

    record Failed(String skillName, String message) implements SkillBodyLoadResult {
      public Failed {
        skillName = requireNonBlank(skillName, "skillName");
        message = requireNonBlank(message, "message");
      }
    }

    private static String requireNonBlank(String value, String name) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(name + " must not be blank");
      }
      return value;
    }
  }
}
