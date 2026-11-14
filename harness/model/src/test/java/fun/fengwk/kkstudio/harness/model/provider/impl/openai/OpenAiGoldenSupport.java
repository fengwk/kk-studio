package fun.fengwk.kkstudio.harness.model.provider.impl.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderProtocolEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRedactedThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallDiagnostic;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 协议全量 wire/终态快照的守护工具。
 *
 * <p>快照以完整文本比对：请求体保留字段顺序与紧凑序列化，流结果保留增量顺序、block 顺序、replay metadata 与用量。重组编码器或累积器时，
 * 任何字段顺序、判断顺序、错误文案或状态赋值时机的变化都会让快照失配。
 *
 * <p>设置系统属性 {@code jmo.golden.dumpDir} 时把实际文本写入该目录，用于在基准提交上重新生成期望值。
 */
public final class OpenAiGoldenSupport {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private static final String DUMP_DIR_PROPERTY = "jmo.golden.dumpDir";

  private OpenAiGoldenSupport() {}

  /**
   * 断言实际输出与基准期望文本逐字符一致。
   *
   * <p>设置 {@code jmo.golden.dumpDir} 时进入生成模式：只写出实际文本而不比对，供在基准提交上一次性重新生成全部期望值。
   */
  public static void assertGolden(String name, String actual) {
    if (dump(name, actual)) {
      return;
    }
    String expected = OpenAiGoldenValues.all().get(name);
    assertNotNull(expected, "missing golden entry: " + name);
    assertEquals(expected, actual, "golden mismatch: " + name);
  }

  private static boolean dump(String name, String actual) {
    String dumpDir = System.getProperty(DUMP_DIR_PROPERTY);
    if (dumpDir == null || dumpDir.isBlank()) {
      return false;
    }
    try {
      Path target = Path.of(dumpDir).resolve(name + ".txt");
      Files.createDirectories(target.getParent());
      Files.writeString(target, actual, StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
    return true;
  }

  /** 渲染一次流的全部可观察事实：原生协议帧、规范化增量序列与完整响应。 */
  public static String renderStream(
      List<ProviderProtocolEvent> protocolEvents,
      List<ProviderStreamEvent> events,
      ProviderResponse response) {
    ObjectNode root = MAPPER.createObjectNode();
    ArrayNode protocolNodes = root.putArray("protocolEvents");
    for (ProviderProtocolEvent protocolEvent : protocolEvents) {
      ObjectNode node = protocolNodes.addObject();
      node.put("eventType", protocolEvent.eventType());
      node.put("data", protocolEvent.data());
    }
    ArrayNode eventNodes = root.putArray("events");
    for (ProviderStreamEvent event : events) {
      eventNodes.add(renderEvent(event));
    }
    ArrayNode blockNodes = root.putArray("blocks");
    for (ProviderContentBlock block : response.blocks()) {
      blockNodes.add(renderBlock(block));
    }
    root.put("stopReason", response.stopReason().name());
    root.set("usage", renderUsage(response.usage()));
    putNullableText(root, "requestId", response.requestId());
    putNullableText(root, "serviceTier", response.serviceTier());
    root.put("rawUsageJson", response.rawUsageJson());
    ArrayNode diagnostics = root.putArray("toolCallDiagnostics");
    for (ProviderToolCallDiagnostic diagnostic : response.toolCallDiagnostics()) {
      diagnostics.add(renderDiagnostic(diagnostic));
    }
    return MAPPER.writeValueAsString(root);
  }

  private static ObjectNode renderEvent(ProviderStreamEvent event) {
    ObjectNode node = MAPPER.createObjectNode();
    switch (event) {
      case ProviderStreamEvent.TextDelta delta -> {
        node.put("kind", "text");
        node.put("text", delta.text());
      }
      case ProviderStreamEvent.ThinkingDelta delta -> {
        node.put("kind", "thinking");
        node.put("text", delta.text());
      }
      case ProviderStreamEvent.ToolCallDelta delta -> {
        node.put("kind", "toolCall");
        node.put("index", delta.index());
        putNullableText(node, "id", delta.id());
        putNullableText(node, "name", delta.name());
        putNullableText(node, "argumentsJson", delta.argumentsJson());
      }
    }
    return node;
  }

  private static ObjectNode renderBlock(ProviderContentBlock block) {
    ObjectNode node = MAPPER.createObjectNode();
    switch (block) {
      case ProviderTextBlock textBlock -> {
        node.put("kind", "text");
        node.put("text", textBlock.text());
      }
      case ProviderThinkingBlock thinkingBlock -> {
        node.put("kind", "thinking");
        node.put("text", thinkingBlock.thinking());
      }
      case ProviderToolCallBlock toolCallBlock -> {
        node.put("kind", "toolCall");
        node.set("call", renderCall(toolCallBlock.toolCall()));
      }
      case ProviderOpaqueBlock opaqueBlock -> {
        node.put("kind", "opaque");
        node.put("blockType", opaqueBlock.blockType());
      }
      case ProviderRedactedThinkingBlock redactedBlock -> {
        node.put("kind", "redactedThinking");
        node.put("data", redactedBlock.data());
      }
      default -> node.put("kind", block.getClass().getSimpleName());
    }
    ProviderBlockData data = blockData(block);
    if (data == null) {
      node.putNull("blockData");
    } else {
      node.set("blockData", renderBlockData(data));
    }
    return node;
  }

  private static ProviderBlockData blockData(ProviderContentBlock block) {
    return switch (block) {
      case ProviderTextBlock textBlock -> textBlock.providerBlockData();
      case ProviderThinkingBlock thinkingBlock -> thinkingBlock.providerBlockData();
      case ProviderToolCallBlock toolCallBlock -> toolCallBlock.providerBlockData();
      case ProviderOpaqueBlock opaqueBlock -> opaqueBlock.providerBlockData();
      case ProviderRedactedThinkingBlock redactedBlock -> redactedBlock.providerBlockData();
      default -> null;
    };
  }

  private static ObjectNode renderBlockData(ProviderBlockData data) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("protocol", data.protocol().name());
    ObjectNode affinity = node.putObject("affinity");
    affinity.put("providerType", data.affinity().providerType().name());
    affinity.put("providerName", data.affinity().providerName());
    affinity.put("modelId", data.affinity().modelId());
    putNullableText(node, "signature", data.signature());
    putNullableText(node, "encryptedContent", data.encryptedContent());
    putNullableText(node, "itemId", data.itemId());
    putNullableText(node, "itemType", data.itemType());
    putNullableText(node, "contentType", data.contentType());
    node.put("refusal", data.refusal());
    if (data.thought() == null) {
      node.putNull("thought");
    } else {
      node.put("thought", data.thought());
    }
    node.put("itemStart", data.itemStart());
    node.set("nativeFields", data.nativeFields());
    return node;
  }

  private static ObjectNode renderCall(ProviderToolCall call) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("id", call.id());
    node.put("name", call.name());
    node.put("argumentsJson", call.argumentsJson());
    putNullableText(node, "historyAction", call.historyAction());
    return node;
  }

  private static ObjectNode renderUsage(ModelUsage usage) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("inputTokens", usage.inputTokens());
    node.put("outputTokens", usage.outputTokens());
    node.put("cacheReadTokens", usage.cacheReadTokens());
    node.put("cacheWriteTokens", usage.cacheWriteTokens());
    ArrayNode ttl = node.putArray("cacheWriteByTtl");
    List<Map.Entry<Long, Long>> entries = new ArrayList<>(usage.cacheWriteByTtl().entrySet());
    entries.sort(Map.Entry.comparingByKey());
    for (Map.Entry<Long, Long> entry : entries) {
      ObjectNode item = ttl.addObject();
      item.put("ttl", entry.getKey());
      item.put("tokens", entry.getValue());
    }
    putNullableNumber(node, "reasoningTokens", usage.reasoningTokens());
    putNullableNumber(node, "providerTotalTokens", usage.providerTotalTokens());
    return node;
  }

  private static ObjectNode renderDiagnostic(ProviderToolCallDiagnostic diagnostic) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("callIndex", diagnostic.callIndex());
    putNullableText(node, "id", diagnostic.id());
    putNullableText(node, "name", diagnostic.name());
    node.put("partialArguments", diagnostic.partialArguments());
    node.put("message", diagnostic.message());
    return node;
  }

  private static void putNullableText(ObjectNode node, String field, String value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }

  private static void putNullableNumber(ObjectNode node, String field, Long value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }
}
