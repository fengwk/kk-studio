package fun.fengwk.kkstudio.core.systemsettings;

/** 向运行时消费者提供当前数据库权威 system settings。 */
public interface SystemSettingsProvider {

  /**
   * 每次调用读取并严格解码当前单行配置；缺失或损坏属于部署不变量错误。
   *
   * @return 当前完整配置聚合
   */
  SystemSettings get();
}
