package fun.fengwk.kkstudio.harness.plugin.api;

import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.util.List;

/**
 * 受信任 build-time 插件贡献的同步纯 Tool。
 *
 * <p>实现只能读取 {@link PluginToolContext} 并返回 {@link PluginToolResult}；任何 durable 变更必须通过 intent 交由
 * Harness Core apply。实现不得自行启动异步执行或接触 Store/gateway/transaction。
 */
public interface PluginTool {

  ToolDescriptor descriptor();

  /** 声明本工具读取或写入的 branch custom state；用于 sibling stale-snapshot 冲突校验。 */
  default List<PluginStateDeclaration> stateAccesses() {
    return List.of();
  }

  PluginToolResult execute(PluginToolContext context, ToolCall call);
}
