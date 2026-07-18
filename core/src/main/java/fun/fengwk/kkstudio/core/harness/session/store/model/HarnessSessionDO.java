package fun.fengwk.kkstudio.core.harness.session.store.model;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code harness_session} 行映射：根/子 Session 与执行指针。 */
@Data
public class HarnessSessionDO {
  /** 业务主键。 */
  private Long id;

  /** 创建时解析的 agent definition id（冻结引用，可变资源不回看）。 */
  private Long agentDefinitionId;

  /** 会话标题。 */
  private String title;

  /** 当前活动 leaf entry（append-only 树 tip）。 */
  private Long leafEntryId;

  /** 当前活动 Run；非空时禁止并发新 Run。 */
  private Long activeRunId;

  /** 父 Session（子代理 child 时有值；根 Session 为空）。 */
  private Long parentSessionId;

  /** 根 Session id（根会话等于自身 id）。 */
  private Long rootSessionId;

  /** 创建此 child Session 的父 ToolInvocation id。 */
  private Long parentInvocationId;

  /** 子代理嵌套深度（根为 0）。 */
  private Integer depth;

  /** 根 Session YOLO 开关（子会话共享根策略）。 */
  private Boolean yoloEnabled;

  /** 乐观锁行版本。 */
  private Long version;

  /** 创建时间（映射 {@code gmt_create}）。 */
  private LocalDateTime createTime;

  /** 更新时间（映射 {@code gmt_modified}）。 */
  private LocalDateTime updateTime;
}
