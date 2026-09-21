package fun.fengwk.kkstudio.platform.harness.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Harness 执行 admission 的部署级容量。
 *
 * <p>这些值是进程启动边界，不属于 SystemSettings、DTO 或前端配置；setter/getter 都做正数校验，使错误值在绑定或装配时 fail-fast。
 */
@Data
@ConfigurationProperties(prefix = "kk-studio.harness.execution-admission")
public class HarnessExecutionAdmissionProperties {

  public static final int DEFAULT_MODEL = 16;
  public static final int DEFAULT_TOOL = 64;
  public static final int DEFAULT_SUBAGENT = 10;
  public static final int DEFAULT_SKILL_SYNC = 8;

  private int model = DEFAULT_MODEL;
  private int tool = DEFAULT_TOOL;
  private int subagent = DEFAULT_SUBAGENT;
  private int skillSync = DEFAULT_SKILL_SYNC;

  public int getModel() {
    return requirePositive(model, "model");
  }

  public void setModel(int model) {
    this.model = requirePositive(model, "model");
  }

  public int getTool() {
    return requirePositive(tool, "tool");
  }

  public void setTool(int tool) {
    this.tool = requirePositive(tool, "tool");
  }

  public int getSubagent() {
    return requirePositive(subagent, "subagent");
  }

  public void setSubagent(int subagent) {
    this.subagent = requirePositive(subagent, "subagent");
  }

  /** Environment Skill Package 同步的最大并发：单 Environment 串行、多个 Environment 并行。 */
  public int getSkillSync() {
    return requirePositive(skillSync, "skill-sync");
  }

  public void setSkillSync(int skillSync) {
    this.skillSync = requirePositive(skillSync, "skill-sync");
  }

  private static int requirePositive(int value, String name) {
    if (value < 1) {
      throw new IllegalArgumentException(
          "kk-studio.harness.execution-admission." + name + " must be at least 1");
    }
    return value;
  }
}
