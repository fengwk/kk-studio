package fun.fengwk.kkstudio.platform.harness.thread.command;

import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.SystemContext;
import fun.fengwk.kkstudio.harness.api.history.SystemPayload;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求 system message 的冻结基线生成与增量变化投影。
 *
 * <p>两条路径由 provider 能力决定：不支持对话中途插入 system message 的 provider 得到一条折叠后的基线（可选追加本次变化）； 支持的 provider 得到按
 * candidate path 顺序重放的基线 + 变化序列，便于缓存命中。
 *
 * <p>投影只读 candidate path 上已持久化的 SYSTEM facts，不重新解析 Agent、Environment 或提示词模板。
 */
final class TurnSystemMessageProjector {

  private TurnSystemMessageProjector() {}

  /** 生成该 provider 与模型适用的 system messages；顺序与折叠点逐字对应 candidate path 的历史事实。 */
  static List<String> project(
      EntryPath path, AgentTurnContext context, ProviderType providerType, ModelDescriptor model) {
    String currentBaseline = String.join("\n\n", context.systemSections().values());
    if (path.requiresSystemBaseline()) {
      return List.of(currentBaseline);
    }
    SystemContext existing = path.systemContext().orElse(null);
    if (existing == null) {
      return List.of(currentBaseline);
    }
    SystemContext next =
        new SystemContext(context.systemSections(), existing.tools(), existing.hostedTools());
    String pendingChange = existing.changesTo(next).map(SystemContext::renderChangeText).orElse("");
    boolean incrementalSystemMessages =
        providerType == ProviderType.ANTHROPIC && model.midConversationSystemMessages();
    if (!incrementalSystemMessages) {
      String collapsed = SystemContext.collapseSystemInstruction(path.entries());
      return List.of(pendingChange.isEmpty() ? collapsed : collapsed + "\n\n" + pendingChange);
    }

    List<String> messages = new ArrayList<>();
    for (Entry entry : path.entries()) {
      if (!(entry.payload() instanceof SystemPayload system)) {
        continue;
      }
      if (system.baseline()) {
        messages.clear();
        messages.add(String.join("\n\n", system.sections().values()));
      } else if (!messages.isEmpty()) {
        String changeText = SystemContext.renderChangeText(system);
        if (!changeText.isEmpty()) {
          messages.add(changeText);
        }
      }
    }
    if (!pendingChange.isEmpty()) {
      messages.add(pendingChange);
    }
    return List.copyOf(messages);
  }
}
