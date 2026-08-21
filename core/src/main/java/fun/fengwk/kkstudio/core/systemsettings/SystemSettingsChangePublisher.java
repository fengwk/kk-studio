package fun.fengwk.kkstudio.core.systemsettings;

/**
 * 系统设置变更的跨节点唤醒。Payload 无语义：订阅方总是回读数据库再替换快照。
 *
 * <p>实现必须把传输失败留在本调用内；不得让 PUT 因唤醒失败而回滚。
 */
@FunctionalInterface
public interface SystemSettingsChangePublisher {

  /** 发布一条唤醒。失败由实现吞掉。 */
  void publish();
}
