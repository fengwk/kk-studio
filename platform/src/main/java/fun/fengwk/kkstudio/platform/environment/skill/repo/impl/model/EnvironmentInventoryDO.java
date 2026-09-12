package fun.fengwk.kkstudio.platform.environment.skill.repo.impl.model;

import lombok.Data;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

/** {@code environment_inventory} 行映射：每个 Environment 恰一行的期望/已应用 Skill 事实。 */
@Data
public class EnvironmentInventoryDO {

  /** Environment 的全局唯一 UUID（PK）。 */
  private UUID environmentId;

  /** Platform 期望的活跃来源集合代际。 */
  private Long sourceSetVersion;

  /** 最近一次被 READY 围栏接受的来源集合代际；从未接受时为 null。 */
  private Long appliedSourceSetVersion;

  /** 已接受 READY 的 capabilities 协议版本。 */
  private Integer capabilitiesVersion;

  /** 已接受 READY 报告的宿主系统 wire 值。 */
  private String operatingSystem;

  /** 已接受 READY 报告的 IANA 时区 ID。 */
  private String timeZone;

  /** 已接受 READY 报告的备注。 */
  private String note;

  /** 已接受 READY 报告的 Daemon canonical Environment root。 */
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
