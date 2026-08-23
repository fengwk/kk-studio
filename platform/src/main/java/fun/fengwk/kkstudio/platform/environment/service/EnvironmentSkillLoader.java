package fun.fengwk.kkstudio.platform.environment.service;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Platform 端口：按 canonical {@link EnvironmentName} 向 READY 的 live Environment 请求完整 skill 正文。
 *
 * <p>由平台 {@code load_skill} 工具包装，供选中 skills 的 Agent 使用。展示名绝不参与路由。
 */
public interface EnvironmentSkillLoader {

  /**
   * 向绑定 {@code environmentName} 的 environment 请求技能 {@code skillName}。
   *
   * <p>成功时以 {@link EnvironmentSkillLoadResult.Loaded} 完成；daemon 失败、environment 离线、断开连接 或超时时以 {@link
   * EnvironmentSkillLoadResult.Failed} 完成。绝不在远程往返期间阻塞调用方线程。
   */
  CompletableFuture<EnvironmentSkillLoadResult> loadSkill(
      EnvironmentName environmentName, String skillName, Duration timeout);
}
