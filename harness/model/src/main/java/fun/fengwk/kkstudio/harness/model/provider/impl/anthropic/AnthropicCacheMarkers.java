package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;

import java.util.List;

/**
 * 按 Anthropic 原生协议在固定边界写入 {@code cache_control} ephemeral 标记。
 *
 * <p>标记只落在最后一个 tools 条目、最后一个合格 system block 与最近历史请求端点上，总标记数不超过 {@link #MAX_CACHE_BREAKPOINTS}。会话
 * {@code key} 不是 Anthropic 原生选项，因此不下发。
 */
final class AnthropicCacheMarkers {

  /** 单个请求的 cache 断点上限。 */
  private static final int MAX_CACHE_BREAKPOINTS = 4;

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private AnthropicCacheMarkers() {}

  static void apply(
      ProviderCacheControl cacheControl,
      ArrayNode messagesArray,
      ArrayNode toolsArray,
      ArrayNode systemArray,
      List<ObjectNode> cacheEndpoints,
      boolean hasDeferredPlaceholder) {
    if (cacheControl.retention() == PromptCacheRetention.NONE) {
      return;
    }

    ObjectNode marker = NODES.objectNode();
    marker.put("type", "ephemeral");
    if (cacheControl.retention() == PromptCacheRetention.LONG) {
      marker.put("ttl", "1h");
    }

    // 只读模式：压缩分支复用主 Thread 已写入的前缀，只在指定消息上放一个断点，绝不写入 tools/system/尾部新缓存。
    if (cacheControl.messageBreakpoint() != null) {
      int index = cacheControl.messageBreakpoint();
      if (index >= 0 && index < messagesArray.size()) {
        ObjectNode endpoint =
            latestEligibleBlock(((ObjectNode) messagesArray.get(index)).path("content"));
        if (endpoint != null) {
          endpoint.set("cache_control", marker);
        }
      }
      return;
    }

    int markersPlaced = 0;

    // 1. TOOLS: 最后一个实际可用 tool，不能标记 encoder-only deferred placeholder。
    int lastRealToolIndex = toolsArray.size() - (hasDeferredPlaceholder ? 2 : 1);
    if (lastRealToolIndex >= 0) {
      ((ObjectNode) toolsArray.get(lastRealToolIndex)).set("cache_control", marker);
      markersPlaced++;
    }

    // 2. SYSTEM: 最后一个合格 system block
    ObjectNode lastSystemBlock = latestEligibleBlock(systemArray);
    if (lastSystemBlock != null) {
      lastSystemBlock.set("cache_control", marker);
      markersPlaced++;
    }

    // 3. CONVERSATION: 剩余预算用于最近历史请求端点与当前尾部；最近端点优先。
    for (int i = cacheEndpoints.size() - 1; i >= 0 && markersPlaced < MAX_CACHE_BREAKPOINTS; i--) {
      cacheEndpoints.get(i).set("cache_control", marker);
      markersPlaced++;
    }
  }

  /** 候选必须引用最终 wire 中的同一对象；相同文本不是相同端点，连续空/opaque 消息则不重复占预算。 */
  static void addEndpoint(List<ObjectNode> endpoints, ObjectNode endpoint) {
    if (endpoint != null
        && (endpoints.isEmpty() || endpoints.get(endpoints.size() - 1) != endpoint)) {
      endpoints.add(endpoint);
      if (endpoints.size() > MAX_CACHE_BREAKPOINTS) {
        endpoints.removeFirst();
      }
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
