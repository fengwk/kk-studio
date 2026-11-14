package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderStreamBridge;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * Anthropic Messages 的 indexed content block 状态机。
 *
 * <p>按原生 index 维护严格 indexed content block 状态机，支持多工具调用交错 delta 与连续 toolOrdinal。每个 block 的 {@code
 * content_block_start} 对象都是 replay 事实源：终态只覆盖 normalized 字段（text、thinking/signature、 redacted
 * data、tool input、citations），官方附加字段原样保留。官方 delta 只有 {@code text_delta}、{@code
 * input_json_delta}、{@code thinking_delta}、{@code signature_delta}、{@code
 * citations_delta}：只接受可证明安全的 block/delta 配对，未知配对显式失败而不是猜测性合并。
 *
 * <p>未知但合法的 provider block（如 server tool use）本身原样保留，携带 {@code input_json_delta} 时按 deterministic
 * 规则累积 partial JSON，终态解析为 {@code input} 对象。
 */
final class AnthropicContentBlocks {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private final ProviderStreamBridge bridge;

  private final Map<Integer, AnthropicWireBlock> blocksByIndex = new TreeMap<>();

  private Integer lastBlockIndex = null;
  private int nextToolOrdinal = 0;

  AnthropicContentBlocks(ProviderStreamBridge bridge) {
    this.bridge = bridge;
  }

  /** 按原生 index 升序排列的 blocks：终态必须严格保持原生 block index 顺序。 */
  Collection<AnthropicWireBlock> ordered() {
    return blocksByIndex.values();
  }

  /** 是否所有已开始的 block 都已收到 {@code content_block_stop}。 */
  boolean allStopped() {
    for (AnthropicWireBlock block : blocksByIndex.values()) {
      if (block.state != AnthropicWireBlock.Lifecycle.STOPPED) {
        return false;
      }
    }
    return true;
  }

  void start(JsonNode root) {
    JsonNode indexNode = root.get("index");
    if (indexNode == null || !indexNode.isIntegralNumber() || !indexNode.canConvertToInt()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "invalid content_block_start index");
    }
    int index = indexNode.asInt(0);
    if (index < 0 || (lastBlockIndex != null && index <= lastBlockIndex)) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE,
          "duplicate or out-of-order content_block_start index");
    }
    lastBlockIndex = index;

    JsonNode blockNode = root.get("content_block");
    if (blockNode == null || !blockNode.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "content_block_start missing content_block object");
    }
    if (!blockNode.has("type") || !blockNode.get("type").isString()) {
      throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "content_block missing type");
    }

    String type = blockNode.get("type").stringValue();
    // start 对象是所有 block 的 replay 基座：终态只覆盖 normalized 字段，官方附加字段原样保留
    AnthropicWireBlock accumulator =
        new AnthropicWireBlock(type, (ObjectNode) blockNode.deepCopy());

    switch (type) {
      case "text" -> {
        if (blockNode.has("text")) {
          if (!blockNode.get("text").isString()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE, "text content_block text must be string");
          }
          String text = blockNode.get("text").stringValue();
          if (!text.isEmpty()) {
            accumulator.textBuffer.append(text);
            bridge.emitEvent(new ProviderStreamEvent.TextDelta(text));
          }
        }
        if (blockNode.has("citations")) {
          // start 已声明 citations 时，后续 citations_delta 依序追加在其后
          accumulator.citations = requireCitationArray(blockNode.get("citations"));
        }
      }
      case "thinking" -> {
        if (blockNode.has("thinking")) {
          if (!blockNode.get("thinking").isString()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE,
                "thinking content_block thinking must be string");
          }
          String thinking = blockNode.get("thinking").stringValue();
          if (!thinking.isEmpty()) {
            accumulator.thinkingBuffer.append(thinking);
            bridge.emitEvent(new ProviderStreamEvent.ThinkingDelta(thinking));
          }
        }
        if (blockNode.has("signature")) {
          if (!blockNode.get("signature").isString()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE, "thinking signature must be string");
          }
          accumulator.signature = blockNode.get("signature").stringValue();
        }
      }
      case "redacted_thinking" -> {
        if (blockNode.has("data")) {
          if (!blockNode.get("data").isString()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE, "redacted_thinking data must be string");
          }
          accumulator.redactedData = blockNode.get("data").stringValue();
        }
      }
      case "tool_use" -> {
        if (!blockNode.has("id")
            || !blockNode.get("id").isString()
            || !blockNode.has("name")
            || !blockNode.get("name").isString()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE,
              "tool_use content_block missing valid id or name");
        }
        accumulator.toolId = blockNode.get("id").stringValue();
        accumulator.toolName = blockNode.get("name").stringValue();
        if (accumulator.toolId.isBlank() || accumulator.toolName.isBlank()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE,
              "tool_use content_block missing valid id or name");
        }
        accumulator.toolOrdinal = nextToolOrdinal++;
        String initialArguments = null;
        if (blockNode.has("input")) {
          JsonNode inputNode = blockNode.get("input");
          if (!inputNode.isObject()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE, "tool_use input must be a JSON object");
          }
          if (inputNode.isEmpty()) {
            accumulator.initialInputPlaceholder = true;
          } else {
            initialArguments = inputNode.toString();
            accumulator.toolArgsBuffer.append(initialArguments);
            accumulator.initialArgumentsComplete = true;
          }
        }
        bridge.emitEvent(
            new ProviderStreamEvent.ToolCallDelta(
                accumulator.toolOrdinal,
                accumulator.toolId,
                accumulator.toolName,
                initialArguments));
      }
      default -> {
        // 未知或未来新增 block type：不产生 normalized 语义，raw block 原样保留供 opaque replay
      }
    }

    blocksByIndex.put(index, accumulator);
  }

  void delta(JsonNode root) {
    JsonNode indexNode = root.get("index");
    if (indexNode == null || !indexNode.isIntegralNumber() || !indexNode.canConvertToInt()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "invalid content_block_delta index");
    }
    int index = indexNode.asInt(0);
    AnthropicWireBlock block = blocksByIndex.get(index);
    if (block == null || block.state != AnthropicWireBlock.Lifecycle.ACTIVE) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "unexpected content_block_delta for index: " + index);
    }

    JsonNode deltaNode = root.get("delta");
    if (deltaNode == null || !deltaNode.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "content_block_delta missing delta object");
    }
    if (!deltaNode.has("type") || !deltaNode.get("type").isString()) {
      throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "delta object missing type");
    }

    String deltaType = deltaNode.get("type").stringValue();
    switch (block.type) {
      case "text" -> {
        switch (deltaType) {
          case "text_delta" -> {
            if (!deltaNode.has("text") || !deltaNode.get("text").isString()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_RESPONSE, "text_delta text must be string");
            }
            String text = deltaNode.get("text").stringValue();
            if (!text.isEmpty()) {
              block.textBuffer.append(text);
              bridge.emitEvent(new ProviderStreamEvent.TextDelta(text));
            }
          }
          case "citations_delta" -> {
            if (!deltaNode.has("citation") || !deltaNode.get("citation").isObject()) {
              throw new ProviderException(
                  ProviderErrorKind.INVALID_RESPONSE, "citations_delta citation must be an object");
            }
            if (block.citations == null) {
              block.citations = NODES.arrayNode();
            }
            // citation 是官方可扩展结构：原样依序累积，不做字段白名单
            block.citations.add(deltaNode.get("citation").deepCopy());
          }
          default -> throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "mismatched delta type for text block");
        }
      }
      case "thinking" -> {
        if ("thinking_delta".equals(deltaType)) {
          if (!deltaNode.has("thinking") || !deltaNode.get("thinking").isString()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE, "thinking_delta thinking must be string");
          }
          String thinking = deltaNode.get("thinking").stringValue();
          if (!thinking.isEmpty()) {
            block.thinkingBuffer.append(thinking);
            bridge.emitEvent(new ProviderStreamEvent.ThinkingDelta(thinking));
          }
        } else if ("signature_delta".equals(deltaType)) {
          if (!deltaNode.has("signature") || !deltaNode.get("signature").isString()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE, "signature_delta signature must be string");
          }
          String sig = deltaNode.get("signature").stringValue();
          if (block.signature == null) {
            block.signature = sig;
          } else {
            block.signature += sig;
          }
        } else {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "mismatched delta type for thinking block");
        }
      }
      case "tool_use" -> {
        if (!"input_json_delta".equals(deltaType)) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "mismatched delta type for tool_use block");
        }
        if (block.initialArgumentsComplete) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "tool call arguments already finalized");
        }
        String partialJson =
            requirePartialJson(deltaNode, "input_json_delta partial_json must be string");
        if (!partialJson.isEmpty()) {
          block.toolArgsBuffer.append(partialJson);
          bridge.emitEvent(
              new ProviderStreamEvent.ToolCallDelta(block.toolOrdinal, null, null, partialJson));
        }
      }
      case "redacted_thinking" -> throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE,
          "redacted_thinking block must be complete and carries no delta");
      default -> applyNativeDelta(block, deltaType, deltaNode);
    }
  }

  /**
   * 原生（非 normalized）block 的唯一可安全组装 delta 是 {@code input_json_delta}：partial JSON 依序累积，终态解析为 {@code
   * input} 对象。其余 delta 无法证明可保真组装，显式失败而不是猜测性合并或静默丢弃。
   */
  private static void applyNativeDelta(
      AnthropicWireBlock block, String deltaType, JsonNode deltaNode) {
    if (!"input_json_delta".equals(deltaType)) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE,
          "unsupported delta type for native block: " + deltaType);
    }
    if (block.rawBlock.has("input")) {
      JsonNode declaredInput = block.rawBlock.get("input");
      if (!declaredInput.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "native block input must be a JSON object");
      }
      // 空对象占位可被 delta 累积结果替换；非空 input 无法与 delta 无损合并
      if (!declaredInput.isEmpty()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE,
            "native block input_json_delta conflicts with non-empty input already declared");
      }
    }
    String partialJson =
        requirePartialJson(deltaNode, "input_json_delta partial_json must be string");
    if (block.nativeInputBuffer == null) {
      block.nativeInputBuffer = new StringBuilder();
    }
    block.nativeInputBuffer.append(partialJson);
  }

  private static String requirePartialJson(JsonNode deltaNode, String errorMessage) {
    if (!deltaNode.has("partial_json") || !deltaNode.get("partial_json").isString()) {
      throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, errorMessage);
    }
    return deltaNode.get("partial_json").stringValue();
  }

  /** citation 集合只校验容器与元素形状，官方未来新增的 citation 字段一律无损保留。 */
  private static ArrayNode requireCitationArray(JsonNode citationsNode) {
    if (!citationsNode.isArray()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "content_block citations must be an array");
    }
    ArrayNode citations = NODES.arrayNode();
    for (JsonNode citation : citationsNode) {
      if (!citation.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "content_block citations must contain objects");
      }
      citations.add(citation.deepCopy());
    }
    return citations;
  }

  void stop(JsonNode root) {
    JsonNode indexNode = root.get("index");
    if (indexNode == null || !indexNode.isIntegralNumber() || !indexNode.canConvertToInt()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "invalid content_block_stop index");
    }
    int index = indexNode.asInt(0);
    AnthropicWireBlock block = blocksByIndex.get(index);
    if (block == null || block.state != AnthropicWireBlock.Lifecycle.ACTIVE) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "unexpected content_block_stop for index: " + index);
    }
    block.state = AnthropicWireBlock.Lifecycle.STOPPED;
  }
}
