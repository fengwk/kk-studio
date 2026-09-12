package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;

/**
 * Environment 当前 registrationToken 的只读投影。
 *
 * <p>读取是幂等只读动作：返回当前存储的单份 token 与当时的 CAS 版本，不轮换、不更新 version/updateTime。响应必须禁止缓存 （{@code
 * Cache-Control: no-store}），且不得进入通用列表/详情投影。
 */
@Data
public class EnvironmentRegistrationTokenDTO {

  /** Environment 稳定身份（canonical UUID 文本）。 */
  private String id;

  /** 当前 registrationToken 原值，仅用于按需复制给 Daemon 启动参数。 */
  private String registrationToken;

  /** 读取时刻的 CAS 版本，便于调用方判断随后轮换造成的版本变化。 */
  private String version;
}
