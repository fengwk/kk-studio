package fun.fengwk.kkstudio.harness.runtime.port;

/**
 * Tool outcome 的永久物化失败：输入引用已确定不可被稳定表达（未就绪、完整性不符或不是已授权资源）。
 *
 * <p>抛出时调用方事务必须整体回滚；Runtime 随后以无资源 UNKNOWN 收敛该 terminal 事实，绝不重试工具外部副作用或再次消费同一 upload。 数据库 /
 * 存储基础设施不可用不属于本异常，必须原样传播以等待恢复。
 */
public class ToolOutcomeMaterializationException extends RuntimeException {

  public ToolOutcomeMaterializationException(String message) {
    super(message);
  }

  public ToolOutcomeMaterializationException(String message, Throwable cause) {
    super(message, cause);
  }
}
