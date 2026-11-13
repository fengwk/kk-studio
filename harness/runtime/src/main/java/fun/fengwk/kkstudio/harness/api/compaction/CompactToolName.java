package fun.fengwk.kkstudio.harness.api.compaction;

/**
 * 内置 {@code compact} 工具的模型可见名称。
 *
 * <p>名称是 Contributor 定义与 Runtime 排队识别共用的产品契约：Contributor 用它注册工具，Runtime 按 durable ToolResult
 * 元数据中的同一名称识别已排队的压缩请求，因此两侧都不复制字符串，Runtime 也不需要 import Contributor 实现。
 */
public final class CompactToolName {

  public static final String NAME = "compact";

  private CompactToolName() {}
}
