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
 * <p>不支持中途 system message 的 provider 使用本轮当前段落；支持的 provider 按 candidate path 重放基线与增量，再加入本轮待提交变化。
 * 段落渲染不依赖 JSON 对象键顺序；预算估算与最终请求使用相同投影，不累积被替换的正文。
 */
final class TurnSystemMessageProjector {

  private TurnSystemMessageProjector() {}

  /** 生成当前请求的 system 文本列表；待提交变化也计入预算，增量协议保留历史位置。 */
  static List<String> project(
      EntryPath path, AgentTurnContext context, ProviderType providerType, ModelDescriptor model) {
    String currentBaseline =
        new SystemContext(context.systemSections(), List.of(), List.of()).systemInstruction();
    if (path.requiresSystemBaseline()) {
      return List.of(currentBaseline);
    }
    SystemContext existing = path.systemContext().orElse(null);
    if (existing == null) {
      return List.of(currentBaseline);
    }
    boolean incrementalSystemMessages =
        providerType == ProviderType.ANTHROPIC && model.midConversationSystemMessages();
    if (!incrementalSystemMessages) {
      return List.of(currentBaseline);
    }
    SystemContext next =
        new SystemContext(context.systemSections(), existing.tools(), existing.hostedTools());
    String pendingChange = existing.changesTo(next).map(SystemContext::renderChangeText).orElse("");

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
