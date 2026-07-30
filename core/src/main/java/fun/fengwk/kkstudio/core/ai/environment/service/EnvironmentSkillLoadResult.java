package fun.fengwk.kkstudio.core.ai.environment.service;

import java.util.Objects;

/** Bounded asynchronous result of a complete skill load request. */
public sealed interface EnvironmentSkillLoadResult
    permits EnvironmentSkillLoadResult.Loaded, EnvironmentSkillLoadResult.Failed {

  String skillName();

  /** Daemon returned the full SKILL.md body. */
  record Loaded(String skillName, String content) implements EnvironmentSkillLoadResult {
    public Loaded {
      skillName = requireNonBlank(skillName, "skillName");
      content = Objects.requireNonNull(content, "content");
    }
  }

  /** Offline, timeout, disconnect, or daemon-reported failure. */
  record Failed(String skillName, String message) implements EnvironmentSkillLoadResult {
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
