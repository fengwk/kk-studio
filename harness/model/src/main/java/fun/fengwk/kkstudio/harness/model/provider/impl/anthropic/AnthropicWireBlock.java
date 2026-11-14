package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 单个 Anthropic content block 的累积状态：按原生 index 维护，严格 indexed 生命周期。
 *
 * <p>{@code rawBlock} 是 provider 给出的原始 {@code content_block_start} 对象，也是该 block 的 replay 基座：终态只覆盖
 * normalized 字段，官方附加字段原样保留。
 */
final class AnthropicWireBlock {

  /** content block 生命周期：只有 ACTIVE 接受 delta 与 stop。 */
  enum Lifecycle {
    ACTIVE,
    STOPPED
  }

  final String type;

  /** provider 给出的原始 start 对象：所有 block 的 replay 基座，终态只覆盖 normalized 字段。 */
  final ObjectNode rawBlock;

  Lifecycle state = Lifecycle.ACTIVE;

  final StringBuilder textBuffer = new StringBuilder();

  /** text block 累积到的 citation 列表；null 表示上游未下发 citations_delta（start 自带的 citations 原样保留）。 */
  ArrayNode citations = null;

  final StringBuilder thinkingBuffer = new StringBuilder();
  String signature = null;
  String redactedData = null;

  int toolOrdinal = -1;
  String toolId = null;
  String toolName = null;
  final StringBuilder toolArgsBuffer = new StringBuilder();
  boolean initialInputPlaceholder = false;
  boolean initialArgumentsComplete = false;

  /** 原生（非 normalized）block 累积到的 input_json_delta partial JSON；null 表示上游未下达该类 delta。 */
  StringBuilder nativeInputBuffer = null;

  AnthropicWireBlock(String type, ObjectNode rawBlock) {
    this.type = type;
    this.rawBlock = rawBlock;
  }
}
