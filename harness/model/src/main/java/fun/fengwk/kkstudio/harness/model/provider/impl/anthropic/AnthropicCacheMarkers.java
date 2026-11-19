package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;

/**
 * 按 Anthropic 原生协议在固定边界写入 {@code cache_control} ephemeral 标记。
 *
 * <p>常规请求只标最后一个 tools 条目、最后一个合格 system block 与当前对话尾部。会话 {@code key} 不是 Anthropic 原生选项，因此不下发。
 */
final class AnthropicCacheMarkers {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private AnthropicCacheMarkers() {}

  static void apply(
      PromptCacheRetention retention,
      ArrayNode toolsArray,
      ArrayNode systemArray,
      AnthropicWireMessages.Result wire,
      boolean reusePrefix,
      boolean hasDeferredPlaceholder,
      boolean automaticCache) {
    if (retention == PromptCacheRetention.NONE || automaticCache) {
      return;
    }

    ObjectNode marker = NODES.objectNode();
    marker.put("type", "ephemeral");
    if (retention == PromptCacheRetention.LONG) {
      marker.put("ttl", "1h");
    }

    // 仅尝试复用指定前缀；目标已移除或不能标记时，不改标其它位置。
    if (reusePrefix) {
      if (wire.prefixEndpoint() != null) {
        wire.prefixEndpoint().set("cache_control", marker);
      }
      return;
    }

    // TOOLS: 最后一个实际可用 tool，不能标记 encoder-only deferred placeholder。
    int lastRealToolIndex = toolsArray.size() - (hasDeferredPlaceholder ? 2 : 1);
    if (lastRealToolIndex >= 0) {
      ((ObjectNode) toolsArray.get(lastRealToolIndex)).set("cache_control", marker);
    }

    // SYSTEM: 最后一个合格 system block
    ObjectNode lastSystemBlock = latestEligibleBlock(systemArray);
    if (lastSystemBlock != null) {
      lastSystemBlock.set("cache_control", marker);
    }

    // CONVERSATION: 常规请求只标记当前尾部，不回填历史请求边界。
    if (wire.cacheEndpoint() != null) {
      wire.cacheEndpoint().set("cache_control", marker);
    }
  }

  /** 保守选择本地支持的官方可缓存块，不猜测未知块或签名块的格式，也不清洗空 text。 */
  static ObjectNode latestEligibleBlock(JsonNode contents) {
    for (int i = contents.size() - 1; i >= 0; i--) {
      JsonNode block = contents.get(i);
      boolean eligible =
          switch (block.path("type").asString("")) {
            case "text" -> block.path("text").isString()
                && !block.path("text").stringValue().isEmpty();
            case "image", "document", "tool_use", "tool_result" -> true;
            default -> false;
          };
      if (eligible) {
        return (ObjectNode) block;
      }
    }
    return null;
  }
}
