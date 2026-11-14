package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallDiagnostic;

import java.util.List;

/**
 * 由终态 content blocks 装配出的响应内容。
 *
 * @param blocks 按原生 index 升序排列的响应 blocks
 * @param toolCallDiagnostics 工具调用诊断；{@code FILTERED} 时由调用方清空
 */
record AnthropicAssembledResponse(
    List<ProviderContentBlock> blocks, List<ProviderToolCallDiagnostic> toolCallDiagnostics) {}
