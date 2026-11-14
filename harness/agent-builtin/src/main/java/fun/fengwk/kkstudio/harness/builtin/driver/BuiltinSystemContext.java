package fun.fengwk.kkstudio.harness.builtin.driver;

import fun.fengwk.kkstudio.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.SystemContext;
import fun.fengwk.kkstudio.harness.api.history.SystemHostedTool;
import fun.fengwk.kkstudio.harness.api.history.SystemPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.util.List;

/**
 * 本次 turn 需要写入的 system context 变更：baseline、增量 sections / tools，或暂停后的纯文本通知。
 *
 * <p>纯函数：只读 frozen spec 与 candidate path，不读写 Store。
 */
final class BuiltinSystemContext {

  private static final SchemaJsonCodec SCHEMA_CODEC = new SchemaJsonCodec();

  private BuiltinSystemContext() {}

  /**
   * 计算本次 turn 的 system change。
   *
   * <p>COMPACTION turn 不写 system change；缺少 baseline 时写完整 baseline；否则写增量变更。assistant 以 CONTINUE
   * 暂停后只通知文本变更， 不改变工具集合。
   */
  static SystemPayload change(ThreadState thread, EntryPath path, ModelRequestSpec spec) {
    List<ProviderToolDefinition> clientTools = modelToolDefinitions(spec.toolBindings());
    SystemContext resolved =
        new SystemContext(spec.systemSections(), clientTools, hostedTools(spec));
    if (thread.kind() == ThreadKind.COMPACTION) {
      return null;
    }
    if (path.requiresSystemBaseline()) {
      return baseline(spec, clientTools);
    }
    SystemPayload change = path.systemContext().orElseThrow().changesTo(resolved).orElse(null);
    if (change == null || !latestAssistantPaused(path)) {
      return change;
    }
    if (change.sections().isEmpty() && change.removedSections().isEmpty()) {
      return null;
    }
    return new SystemPayload(
        change.sections(),
        change.removedSections(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        false);
  }

  private static List<SystemHostedTool> hostedTools(ModelRequestSpec spec) {
    if (spec.providerType() != ProviderType.ANTHROPIC) {
      return List.of();
    }
    return spec.variant().protocolOptions().toolDefinitions().stream()
        .map(tool -> new SystemHostedTool(tool.name(), tool.definitionJson()))
        .toList();
  }

  private static boolean latestAssistantPaused(EntryPath path) {
    List<Entry> entries = path.entries();
    for (int index = entries.size() - 1; index >= 0; index--) {
      if (entries.get(index).payload() instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.ASSISTANT
          && message.assistantMetadata() != null) {
        return message.assistantMetadata().stopReason() == GenerationStopReason.CONTINUE;
      }
    }
    return false;
  }

  private static SystemPayload baseline(
      ModelRequestSpec spec, List<ProviderToolDefinition> clientTools) {
    return new SystemPayload(
        spec.systemSections(),
        List.of(),
        clientTools,
        List.of(),
        hostedTools(spec),
        List.of(),
        true);
  }

  private static List<ProviderToolDefinition> modelToolDefinitions(List<ToolBinding> bindings) {
    return bindings.stream()
        .map(
            binding -> {
              ToolDescriptor descriptor = binding.descriptor();
              return new ProviderToolDefinition(
                  descriptor.name(),
                  descriptor.description(),
                  SCHEMA_CODEC.encode(descriptor.inputSchema()));
            })
        .toList();
  }
}
