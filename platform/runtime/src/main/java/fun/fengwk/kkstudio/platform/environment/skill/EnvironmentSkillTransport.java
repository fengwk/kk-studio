package fun.fengwk.kkstudio.platform.environment.skill;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.DaemonSkillPackage;

import java.util.List;

/**
 * 技能期望集合的下发窄端口：由 Environment 会话核心实现，编排器只依赖这条能力。
 *
 * <p>实现必须在连接不 READY 或出站队列已满时抛出稳定异常，而不是阻塞或静默丢弃；调用方以「下一次触发重新收敛」处理失败。
 */
public interface EnvironmentSkillTransport {

  /** 向指定 Environment 的 READY 连接下发完整的技能期望集合。 */
  void sendSkillSync(EnvironmentId environmentId, List<DaemonSkillPackage> packages);
}
