package fun.fengwk.kkstudio.harness.runtime.skill;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 从指定 Environment 加载完整 SKILL.md body，不暴露本地路径。
 *
 * <p>在 Core 的 Environment skill loader 之上做的轻量 Runtime port，使 PLATFORM tool 不直接依赖 Host lifecycle
 * bean。
 */
public interface SkillBodyLoader {

  CompletableFuture<SkillBodyLoadResult> load(
      String environmentName, String skillName, Duration timeout);

  /** 有界 skill body 加载结果。 */
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
