package fun.fengwk.kkstudio.share.ai.environment;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import lombok.ToString;

/**
 * 创建 Environment Skill 来源请求 DTO。
 *
 * <p>{@code type} 是稳定 wire 值 {@code path|git}：PATH 只携带 {@code path}，GIT 携带 {@code gitUrl} 与可选的
 * {@code gitRef}/{@code scanPath}；属于另一类型的字段必须缺省，同时出现即校验失败。{@code defaultSource} 由服务端拥有（创建
 * Environment 时生成的缺省 PATH 来源），客户端不可配置。
 *
 * <p>{@code gitUrl} 可能携带用户凭据，因此不进入 {@code toString()}，避免被日志顺带写出。
 */
@Data
public class EnvironmentSkillSourceCreateDTO {

  /** 来源类型 wire 值：{@code path} / {@code git}。 */
  private String type;

  /** PATH 来源目录：{@code ~/...} 或目标 OS 词法绝对路径。 */
  @ToString.Exclude private String path;

  /** GIT 来源仓库 URL。 */
  @ToString.Exclude private String gitUrl;

  /** GIT 来源可选 ref；缺省表示跟踪远端默认 HEAD。 */
  @ToString.Exclude private String gitRef;

  /** GIT 来源可选仓库内相对扫描目录；缺省表示仓库根。 */
  @ToString.Exclude private String scanPath;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("unknown environment skill source field: " + fieldName);
  }
}
