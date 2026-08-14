package fun.fengwk.kkstudio.core.ai.chat.repo.impl.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** {@code chat} 行映射：持久化的多 Session 集合。 */
@Data
public class ChatDO {

  /** 业务主键（PostgreSQL uuid，由应用生成）。 */
  private UUID id;

  /** 标题，必填：非空白且不超过 256 字符。 */
  private String title;

  /** 必填 Agent definition name；不建立外键，Agent 硬删除期间该名称暂时无法解析，同名重建后重新生效。 */
  private String agentName;

  /** 可空的默认分支 Environment 逻辑路由名称（与 {@code workspacePath} 同存同空）。 */
  private String environmentName;

  /** 可空的默认分支 Environment workspace path（与 {@code environmentName} 同存同空）。 */
  private String workspacePath;

  /** YOLO 模式开关：true 时工具调用跳过权限评估直接 Allow；创建时未显式指定则取部署级 ToolSettings 的 defaultYolo。 */
  private boolean yoloEnabled;

  /** 乐观锁行版本：非负，从 0 开始，每次写操作 +1；CAS 更新依据。 */
  private Long version;

  /** 创建时间（映射 {@code created_at} timestamptz，毫秒精度）。 */
  private Instant createTime;

  /** 更新时间（映射 {@code updated_at} timestamptz，应用侧维护，毫秒精度）。 */
  private Instant updateTime;
}
