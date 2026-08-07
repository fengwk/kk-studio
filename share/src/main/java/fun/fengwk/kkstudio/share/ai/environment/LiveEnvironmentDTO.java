package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * 单个服务端内存 live Environment 注册表条目的紧凑读模型。
 *
 * <p>{@code name} 是 canonical 逻辑路由身份（也是唯一键）；不存在独立的展示名或 UUID。
 */
@Data
public class LiveEnvironmentDTO {
  /** live Environment 注册表条目的唯一路由身份：canonical bounded 小写路由名称。 */
  private String name;

  /** 连接生命周期状态，取 {@code LiveEnvironmentStatus} 枚举名：CONNECTING（HELLO 已接受，READY 未完成）或 READY（可派发）。 */
  private String status;

  /** 按统一可用性规则（READY + 连接打开 + 心跳未过期）计算的可用标记，供前端标注 unavailable。 */
  private boolean ready;

  /** 最近一次活跃时间（UTC Instant，注册表心跳/更新维护）。 */
  private Instant lastSeen;

  /** 该 Environment 发布的工具能力列表（由 EnvironmentToolCatalog 固定，与 daemon 技能无关）。 */
  private List<LiveEnvironmentToolDTO> tools;

  /** 该 Environment 的 daemon 声明的技能能力列表（仅 READY 状态发布）。 */
  private List<LiveEnvironmentSkillDTO> skills;
}
