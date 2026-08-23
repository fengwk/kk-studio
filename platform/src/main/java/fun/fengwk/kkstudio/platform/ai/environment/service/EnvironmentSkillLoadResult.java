package fun.fengwk.kkstudio.platform.ai.environment.service;

import java.util.Objects;

/** 一次完整 skill 加载请求的有界异步结果。 */
public sealed interface EnvironmentSkillLoadResult
    permits EnvironmentSkillLoadResult.Loaded, EnvironmentSkillLoadResult.Failed {

  String skillName();

  /** Daemon 返回了完整 SKILL.md 正文。 */
  record Loaded(String skillName, String content) implements EnvironmentSkillLoadResult {
    public Loaded {
      skillName = requireNonBlank(skillName, "skillName");
      content = Objects.requireNonNull(content, "content");
    }
  }

  /** 离线、超时、断开连接或 daemon 上报的失败。 */
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
