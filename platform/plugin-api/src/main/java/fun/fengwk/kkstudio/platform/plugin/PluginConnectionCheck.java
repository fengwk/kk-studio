package fun.fengwk.kkstudio.platform.plugin;

/**
 * Plugin 运行期实例可选声明的显式只读连接检查能力。
 *
 * <p>它是纯粹的按需能力：只有管理面一次显式检查请求才会调用 {@link #check()}；保存或读取配置、启动、目录对账与任务执行都绝不触发它，也没有独立的注册中心。 实例未声明该能力时
 * {@link PluginInstance#connectionCheck()} 返回空，管理面据此拒绝检查请求。
 *
 * <p>{@link #check()} 只做只读探测，绝不提交作业、生成内容或写入远端状态；它返回的 {@link PluginConnectionCheckResult}
 * 只有类型化维度，不携带异常消息、远端 URI、远端正文或任何秘密。所有可观测的失败都必须被分类为维度（无法证明的分类取 {@code
 * INDETERMINATE}），不得把异常文本当作结论，也不得把进程级 {@code Error} 降级为健康结果；线程中断必须保留。
 */
public interface PluginConnectionCheck {

  /** 执行一次只读连接检查；调用方负责保证租用与期望坐标已经在本次调用前判定完成。 */
  PluginConnectionCheckResult check();
}
