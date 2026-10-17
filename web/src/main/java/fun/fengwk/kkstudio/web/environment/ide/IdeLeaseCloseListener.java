package fun.fengwk.kkstudio.web.environment.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.UUID;

/**
 * 一个连接代际关闭时的 IDE 数据面清理钩子：租约失效/接管后必须同时作废入口凭据并关闭已建立的反向流，而不只是撤销票据。
 *
 * <p>由组合根在 {@code EnvironmentSessionListener.onEnvironmentDisconnected} 上转发；实现必须隔离自身异常，不能影响会话状态推进。
 */
public interface IdeLeaseCloseListener {

  void onLeaseClosed(EnvironmentId environmentId, UUID leaseToken);
}
