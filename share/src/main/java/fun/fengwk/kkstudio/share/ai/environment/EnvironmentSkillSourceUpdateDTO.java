package fun.fengwk.kkstudio.share.ai.environment;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import lombok.ToString;

/**
 * 更新 Environment Skill 来源请求 DTO：整体替换来源配置并携带 CAS 期望版本。
 *
 * <p>字段语义与 {@link EnvironmentSkillSourceCreateDTO} 一致。{@code defaultSource} 由服务端拥有，客户端不可配置。
 */
@Data
public class EnvironmentSkillSourceUpdateDTO {

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

  /** 期望的来源行版本（必填，canonical 非负十进制字符串）。 */
  private String expectedVersion;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("unknown environment skill source field: " + fieldName);
  }
}
