package fun.fengwk.kkstudio.share.ai.environment;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.time.Instant;

/**
 * 持久 Skill inventory 响应 DTO：每个来源最新一次成功扫描发现的单个 Skill。
 *
 * <p>身份字段与冻结契约一致：{@code sourceId} + {@code name} 是 Skill 身份，{@code sourceVersion} 是发现它时的来源行版本，
 * {@code contentRevision} + {@code baseDirectory} 唯一确定内容。正文永不入库、也不经由本 DTO 传输。
 */
@Data
public class EnvironmentSkillDTO {

  /** 所属来源的全局唯一 UUID。 */
  private String sourceId;

  /** Skill canonical 名（同一 Environment 内全局唯一）。 */
  private String name;

  /** 发现该 Skill 时的来源行版本（canonical 非负十进制字符串）。 */
  private String sourceVersion;

  /** Skill 描述（非空、无环绕空白）。 */
  private String description;

  /** 宿主上的 Skill 目录（仅事实记录，不在 SQL 解析路径）。 */
  private String baseDirectory;

  /** 内容 revision：小写 64 位 SHA-256。 */
  private String contentRevision;

  /** 最近一次发现该 Skill 的时间。 */
  private Instant discoveredAt;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("unknown environment skill field: " + fieldName);
  }
}
