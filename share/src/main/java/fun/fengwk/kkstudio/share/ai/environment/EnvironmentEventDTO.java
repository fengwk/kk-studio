package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;

import java.time.Instant;

/**
 * Environment 连接与 Skill 同步的运行事件投影。
 *
 * <p>事件是可重建的运行投影的一部分，只记录结构化、去敏的运维事实：不保存 stdout、凭据、Git URL userinfo 或签名地址。管理面按需轮询最近 200
 * 条，不为低频诊断引入实时协议。
 */
@Data
public class EnvironmentEventDTO {

  /** 事件发生时间。 */
  private Instant time;

  /** 级别，取 {@code INFO / WARN / ERROR}。 */
  private String level;

  /**
   * 事件类型，取 {@code CONNECTING / READY / DISCONNECTED / SKILL_SYNC_STARTED / SKILL_SYNC_SUCCEEDED /
   * SKILL_SYNC_FAILED}。
   */
  private String type;

  /** 去敏后的有界说明（不含 stdout、凭据或签名地址）。 */
  private String message;
}
