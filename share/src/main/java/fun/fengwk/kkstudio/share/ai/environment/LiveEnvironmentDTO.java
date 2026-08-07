package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * 单个服务端内存 live Environment 注册表条目的紧凑读模型。
 *
 * <p>{@code id} 是 canonical durable 路由身份（lowercase UUID）；{@code name} 是仅用于展示的标签， 可以在不同 id 之间复用。
 */
@Data
public class LiveEnvironmentDTO {
  /** 服务端内存 live Environment 注册表条目的路由身份：canonical lowercase UUID（非 nil）。 */
  private String id;

  /** 仅展示用的标签（HELLO 时绑定）：可被不同 id 复用，永不参与路由。 */
  private String name;

  /** 连接生命周期状态，取 {@code LiveEnvironmentStatus} 枚举名：CONNECTING（HELLO 已接受，READY 未完成）或 READY（可派发）。 */
  private String status;

  /** 最近一次活跃时间（UTC Instant，注册表心跳/更新维护）。 */
  private Instant lastSeen;

  /** 该 Environment 发布的工具能力列表（由 EnvironmentToolCatalog 固定，与 daemon 技能无关）。 */
  private List<LiveEnvironmentToolDTO> tools;

  /** 该 Environment 的 daemon 声明的技能能力列表（仅 READY 状态发布）。 */
  private List<LiveEnvironmentSkillDTO> skills;
}
