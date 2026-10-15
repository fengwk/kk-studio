package fun.fengwk.kkstudio.platform.plugin.catalog;

/**
 * 触发本节点权威 Plugin 目录对账的聚焦端口。
 *
 * <p>提交成功后的本节点与集群通知都以此请求低延迟回读，不暴露快照或 epoch，也不依赖 Web。 运行期对账调度器实现它，必须只排队，绝不在调用线程配置插件。
 */
public interface PluginCatalogRefreshTrigger {

  /** 请求一次权威目录对账；幂等、线程安全，重复调用可以合并。 */
  void requestRefresh();
}
