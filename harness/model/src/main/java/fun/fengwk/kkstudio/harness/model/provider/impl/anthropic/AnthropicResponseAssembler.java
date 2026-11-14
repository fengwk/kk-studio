package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRedactedThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallDiagnostic;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderJsonHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 把终态 content blocks 装配成有序响应 blocks 与工具调用诊断。
 *
 * <p>blocks 严格按原生 index 升序装配。归一化终止原因决定工具调用语义：{@code FILTERED} 协议撤回工具，{@code CONTINUE} 下归一化 tool
 * call 与续写语义不可共存，{@code LENGTH} 把未完成的工具调用降级为诊断。
 */
final class AnthropicResponseAssembler {

  private final ProviderRequest request;
  private final ProviderDescriptor descriptor;

  AnthropicResponseAssembler(ProviderRequest request, ProviderDescriptor descriptor) {
    this.request = request;
    this.descriptor = descriptor;
  }

  AnthropicAssembledResponse assemble(
      Iterable<AnthropicWireBlock> orderedBlocks, GenerationStopReason stopReason) {
    List<ProviderContentBlock> responseBlocks = new ArrayList<>();
    List<ProviderToolCallDiagnostic> toolCallDiagnostics = new ArrayList<>();
    for (AnthropicWireBlock block : orderedBlocks) {
      assembleBlock(block, stopReason, responseBlocks, toolCallDiagnostics);
    }
    if (stopReason == GenerationStopReason.FILTERED) {
      toolCallDiagnostics.clear();
    }
    return new AnthropicAssembledResponse(responseBlocks, toolCallDiagnostics);
  }

  private void assembleBlock(
      AnthropicWireBlock block,
      GenerationStopReason stopReason,
      List<ProviderContentBlock> responseBlocks,
      List<ProviderToolCallDiagnostic> toolCallDiagnostics) {
    switch (block.type) {
      case "text" -> assembleText(block, responseBlocks);
      case "thinking" -> assembleThinking(block, responseBlocks);
      case "redacted_thinking" -> assembleRedactedThinking(block, responseBlocks);
      case "tool_use" -> assembleToolUse(block, stopReason, responseBlocks, toolCallDiagnostics);
      default -> assembleNative(block, stopReason, responseBlocks);
    }
  }

  private void assembleText(AnthropicWireBlock block, List<ProviderContentBlock> responseBlocks) {
    ObjectNode textFields = block.rawBlock.deepCopy();
    if (block.citations != null) {
      textFields.set("citations", block.citations.deepCopy());
    }
    responseBlocks.add(
        new ProviderTextBlock(block.textBuffer.toString(), blockData(textFields, Set.of("text"))));
  }

  private void assembleThinking(
      AnthropicWireBlock block, List<ProviderContentBlock> responseBlocks) {
    ObjectNode thinkingFields = block.rawBlock.deepCopy();
    thinkingFields.put("signature", block.signature);
    responseBlocks.add(
        new ProviderThinkingBlock(
            block.thinkingBuffer.toString(), blockData(thinkingFields, Set.of("thinking"))));
  }

  private void assembleRedactedThinking(
      AnthropicWireBlock block, List<ProviderContentBlock> responseBlocks) {
    if (block.redactedData == null) {
      return;
    }
    responseBlocks.add(
        new ProviderRedactedThinkingBlock(
            block.redactedData, blockData(block.rawBlock, Set.of("data"))));
  }

  private void assembleToolUse(
      AnthropicWireBlock block,
      GenerationStopReason stopReason,
      List<ProviderContentBlock> responseBlocks,
      List<ProviderToolCallDiagnostic> toolCallDiagnostics) {
    if (stopReason == GenerationStopReason.FILTERED) {
      // FILTERED 协议撤回，忽略工具处理
      return;
    }
    if (stopReason == GenerationStopReason.CONTINUE) {
      // CONTINUE 是撤下工具意图的续写终止态：归一化 tool call 与 native-only 续写语义不可共存，显式失败
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "CONTINUE response must not contain tool calls");
    }
    String argsStr = block.toolArgsBuffer.toString();
    if (argsStr.isEmpty()) {
      if (block.initialInputPlaceholder) {
        // 终态补齐 {}
        addToolCall(block, "{}", responseBlocks);
        return;
      }
      if (stopReason == GenerationStopReason.LENGTH) {
        toolCallDiagnostics.add(
            new ProviderToolCallDiagnostic(
                block.toolOrdinal,
                block.toolId,
                block.toolName,
                null,
                "tool call truncated before arguments received"));
        return;
      }
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "incomplete tool call missing arguments");
    }
    if (ProviderJsonHelper.tryParseJsonObject(argsStr) != null) {
      addToolCall(block, argsStr, responseBlocks);
      return;
    }
    if (stopReason == GenerationStopReason.LENGTH) {
      toolCallDiagnostics.add(
          new ProviderToolCallDiagnostic(
              block.toolOrdinal,
              block.toolId,
              block.toolName,
              argsStr,
              "tool arguments truncated before valid JSON formed"));
      return;
    }
    throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "invalid tool arguments JSON");
  }

  private void addToolCall(
      AnthropicWireBlock block, String argumentsJson, List<ProviderContentBlock> responseBlocks) {
    ProviderToolCall call = new ProviderToolCall(block.toolId, block.toolName, argumentsJson);
    responseBlocks.add(
        new ProviderToolCallBlock(call, blockData(block.rawBlock, Set.of("id", "name", "input"))));
  }

  private void assembleNative(
      AnthropicWireBlock block,
      GenerationStopReason stopReason,
      List<ProviderContentBlock> responseBlocks) {
    ObjectNode obj = block.rawBlock.deepCopy();
    if (block.nativeInputBuffer != null) {
      JsonNode parsedInput =
          ProviderJsonHelper.tryParseJsonObject(block.nativeInputBuffer.toString());
      if (parsedInput == null) {
        if (stopReason != GenerationStopReason.LENGTH) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE,
              "invalid native block input JSON accumulated from input_json_delta");
        }
      } else {
        obj.set("input", parsedInput);
      }
    }
    responseBlocks.add(new ProviderOpaqueBlock(block.type, blockData(obj, Set.of())));
  }

  /**
   * 从 block 的原生事实派生 replay 元数据：只剥离 type 与 normalized 字段，官方附加字段原样保留。
   *
   * <p>{@code signature} 与 {@code data} 是已知语义字段，无论以何种 block 形态到达都不进入 nativeFields。
   */
  private ProviderBlockData blockData(ObjectNode rawBlock, Set<String> normalizedFields) {
    ObjectNode nativeFields = rawBlock.deepCopy();
    nativeFields.remove("type");
    for (String field : normalizedFields) {
      nativeFields.remove(field);
    }
    String signature =
        rawBlock.path("signature").isString() ? rawBlock.path("signature").stringValue() : null;
    nativeFields.remove("signature");
    nativeFields.remove("data");
    return new ProviderBlockData(
        ProviderReplayFormat.ANTHROPIC_MESSAGES,
        descriptor.affinity(request.model().modelId()),
        signature,
        null,
        null,
        nativeFields);
  }
}
