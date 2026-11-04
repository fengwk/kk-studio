package fun.fengwk.kkstudio.harness.api;

/**
 * 公开的 Harness 变更订阅入口：线程版本、执行树与待处理交互。
 *
 * <p>三个源都由平台实现数据库 LISTEN/NOTIFY 协议适配与进程内分发；消费方只依赖本包契约，不需要知道 channel 名、通知 payload 或持久化实现。
 */
public interface HarnessChangeSubscriptions {

  /** Thread durable version 变化。 */
  HarnessThreadVersionChanges threadVersions();

  /** 执行树失效：key 是真实执行根 Thread id。 */
  HarnessInvalidationChanges executionTree();

  /** 待处理交互失效：key 是真实执行根 Thread id，按全局订阅消费。 */
  HarnessInvalidationChanges interactions();
}
