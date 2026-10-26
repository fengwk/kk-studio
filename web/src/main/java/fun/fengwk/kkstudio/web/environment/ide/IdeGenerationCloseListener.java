package fun.fengwk.kkstudio.web.environment.ide;

import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;

/**
 * 一个 IDE 代际结束时的数据面清理钩子：另一个 Daemon 实例接管后必须同时作废入口凭据并关闭已建立的反向流，而不只是撤销票据。
 *
 * <p>由组合根在 {@code EnvironmentSessionListener.onDaemonInstanceReplaced} 上转发；实现必须隔离自身异常，不能影响会话状态推进。
 */
public interface IdeGenerationCloseListener {

  void onGenerationClosed(DaemonConnectionIdentity generation);
}
