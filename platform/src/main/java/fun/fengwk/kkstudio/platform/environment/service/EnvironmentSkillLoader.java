package fun.fengwk.kkstudio.platform.environment.service;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Platform 端口：按 {@link EnvironmentId} 向 READY 的 Environment 请求完整 skill 正文。 */
public interface EnvironmentSkillLoader {

  /**
   * 向绑定 {@code environmentId} 的 environment 请求技能 {@code skillName}。
   *
   * <p>成功时以 {@link EnvironmentSkillLoadResult.Loaded} 完成；daemon 失败、environment 离线、断开连接 或超时时以 {@link
   * EnvironmentSkillLoadResult.Failed} 完成。绝不在远程往返期间阻塞调用方线程。
   */
  CompletableFuture<EnvironmentSkillLoadResult> loadSkill(
      EnvironmentId environmentId, String skillName, Duration timeout);
}
