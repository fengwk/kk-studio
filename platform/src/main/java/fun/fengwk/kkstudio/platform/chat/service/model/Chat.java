package fun.fengwk.kkstudio.platform.chat.service.model;

import lombok.Data;

import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;

import java.time.Instant;
import java.util.UUID;

/** Chat 集合领域行。 */
@Data
public class Chat {

  /** 业务主键（PostgreSQL uuid，由应用生成）。 */
  private UUID id;

  /** 标题，必填：非空白且不超过 256 字符。 */
  private String title;

  /** 必填 Agent definition name；不建立外键，Agent 硬删除期间该名称暂时无法解析，同名重建后重新生效。 */
  private String agentName;

  /** 可空的默认分支完整 Environment binding（路由名 + workspace path，同存同空）：新空面板/线程草稿以此为起点，发送前可更改或清空。 */
  private EnvironmentBinding environment;

  /** YOLO 模式开关：true 时工具调用跳过权限评估直接 Allow；创建时未显式指定则捕获当时数据库 SystemSettings.Tool.defaultYolo。 */
  private boolean yoloEnabled;

  /** 乐观锁版本：非负，从 0 开始，每次写操作 +1；CAS 更新依据。 */
  private Long version;

  /** 创建时间（映射 {@code created_at} timestamptz，毫秒精度）。 */
  private Instant createTime;

  /** 更新时间（映射 {@code updated_at} timestamptz，应用侧维护，毫秒精度）。 */
  private Instant updateTime;
}
