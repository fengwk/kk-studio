package fun.fengwk.kkstudio.core.harness.session.store.model;

import lombok.Data;

import java.time.OffsetDateTime;

/** {@code harness_session} 行映射：final schema（无 root/depth/version 列）。 */
@Data
public class HarnessSessionDO {
  /** 业务主键。 */
  private Long id;

  /** 会话标题。 */
  private String title;

  /** 父 Session（子代理 child 时有值；根 Session 为空）。 */
  private Long parentSessionId;

  /** 创建此 child Session 的父 ToolInvocation id。 */
  private Long parentInvocationId;

  /** 创建时间（映射 {@code created_at}）。 */
  private OffsetDateTime createdAt;

  /** 更新时间（映射 {@code updated_at}）。 */
  private OffsetDateTime updatedAt;
}
