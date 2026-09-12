package fun.fengwk.kkstudio.share.ai.environment;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.time.Instant;

/**
 * Environment 持久 inventory 头响应 DTO：期望来源集合代际与最近一次被围栏接受的 READY 报告。
 *
 * <p>报告列同生同灭：没有已接受报告时全部为 null。{@code appliedSourceSetVersion} 永远不超过 {@code
 * sourceSetVersion}；它落后表示当前期望的来源集合尚未被 READY 确认。
 */
@Data
public class EnvironmentInventoryDTO {

  /** Environment UUID。 */
  private String environmentId;

  /** Platform 期望的活跃来源集合代际（canonical 非负十进制字符串）。 */
  private String sourceSetVersion;

  /** 最近一次被 READY 围栏接受的来源集合代际；从未接受时为 null。 */
  private String appliedSourceSetVersion;

  /** 已接受 READY 的 capabilities 协议版本；从未接受时为 null。 */
  private Integer capabilitiesVersion;

  /** 已接受 READY 报告的宿主系统 wire 值：windows/wsl/linux/macos。 */
  private String operatingSystem;

  /** 已接受 READY 报告的 IANA 时区 ID。 */
  private String timeZone;

  /** 已接受 READY 报告的备注。 */
  private String note;

  /** 已接受 READY 报告的 Daemon canonical Environment root（仅展示）。 */
  private String rootPath;

  /** 该 READY 报告被接受的时间。 */
  private Instant reportedAt;

  private Instant createTime;
  private Instant updateTime;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("unknown environment inventory field: " + fieldName);
  }
}
