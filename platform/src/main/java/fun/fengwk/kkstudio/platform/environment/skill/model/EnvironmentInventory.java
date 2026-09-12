package fun.fengwk.kkstudio.platform.environment.skill.model;

import lombok.Data;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code environment_inventory} 行领域模型：每个 Environment 恰一行的期望/已应用 Skill 事实。
 *
 * <p>{@code sourceSetVersion} 是 Platform 期望的活跃来源集合代际，只有来源集合成员关系变化（创建/删除来源）才推进；{@code
 * appliedSourceSetVersion} 是最近一次被 READY 围栏接受的报告代际，永不超过期望值。报告列同生同灭：没有已接受报告时全部为 null。
 */
@Data
public class EnvironmentInventory {

  /** Environment 的全局唯一 UUID（PK）。 */
  private UUID environmentId;

  /** Platform 期望的活跃来源集合代际（非负，从 0 开始）。 */
  private long sourceSetVersion;

  /** 最近一次被 READY 围栏接受的来源集合代际；从未接受时为 null。 */
  private Long appliedSourceSetVersion;

  /** 已接受 READY 的 capabilities 协议版本。 */
  private Integer capabilitiesVersion;

  /** 已接受 READY 报告的宿主系统 wire 值：windows/wsl/linux/macos。 */
  private String operatingSystem;

  /** 已接受 READY 报告的 IANA 时区 ID。 */
  private String timeZone;

  /** 已接受 READY 报告的备注。 */
  private String note;

  /** 已接受 READY 报告的 Daemon canonical Environment root（仅展示）。 */
  private String rootPath;

  /** 接受该报告的 App 节点实例 UUID。 */
  private UUID ownerNodeId;

  /** 接受该报告时的路由租约代币。 */
  @ToString.Exclude private UUID leaseToken;

  /** 该 READY 报告被接受的时间。 */
  private Instant reportedAt;

  private Instant createTime;
  private Instant updateTime;
}
