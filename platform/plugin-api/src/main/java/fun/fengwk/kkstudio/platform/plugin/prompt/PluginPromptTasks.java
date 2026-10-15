package fun.fengwk.kkstudio.platform.plugin.prompt;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 宿主提供的一次性 Prompt 任务端口：以受信 system 文本与有序内容启动一次独立 Agent 调用，并观察其终态报告。
 *
 * <p>句柄是可持久化、可重启恢复的 {@link UUID}；宿主内部如何派生 join invocation 不暴露给插件。观察语义由宿主保证：非 COMPLETED 的终态只产生受控
 * outcome 错误，绝不回显远端 URI、凭据或 cause。
 */
public interface PluginPromptTasks {

  /**
   * 与接受事务原子提交一次新任务的源 Prompt，返回可持久化的任务句柄。
   *
   * @param agentName 受信 Agent 名；不存在或未配置模型时确定性失败
   * @param systemText 受信 system 文本；由宿主包裹为前导 reminder，空白表示不注入
   * @param contents 有序内容，顺序即最终 USER 消息顺序
   */
  UUID submit(String agentName, String systemText, List<PluginPromptContent> contents);

  /**
   * 等待任务终态并返回最终 Assistant 文本。
   *
   * <p>超时或 {@code continueWaiting} 不再成立时停止本任务后失败，绝不会把未落定的结果当成成功。
   */
  String await(UUID taskId, Duration timeout, BooleanSupplier continueWaiting);

  /** 停止任务：只作用于该任务自身，并容忍任务已经不存在。 */
  void stop(UUID taskId);
}
