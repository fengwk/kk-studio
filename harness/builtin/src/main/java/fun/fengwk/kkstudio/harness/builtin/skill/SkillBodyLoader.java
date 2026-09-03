package fun.fengwk.kkstudio.harness.builtin.skill;

import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 按已 claim 的 Model invocation binding 异步加载 Environment skill 正文的窄端口，不直接依赖宿主 lifecycle。
 *
 * <p>路由只使用冻结 binding 的 {@code environmentId}，workspace path 不参与 skill 加载。
 */
public interface SkillBodyLoader {

  CompletableFuture<SkillBodyLoadResult> load(
      EnvironmentBinding binding, String skillName, Duration timeout);

  /** 有界 skill body 加载结果。 */
  sealed interface SkillBodyLoadResult {

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
      return value.strip();
    }
  }
}
