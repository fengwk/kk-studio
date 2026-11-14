package fun.fengwk.kkstudio.harness.codec;

import static fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport.object;
import static fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport.orderedSet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.api.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.history.SubagentBinding;
import fun.fengwk.kkstudio.harness.api.history.TurnCapabilities;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport;
import fun.fengwk.kkstudio.harness.tool.TurnMediaCapabilities;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * TURN_START 的嵌套值形态：turn capabilities、media modalities 与 compaction start。
 *
 * <p>由 {@link HistoryEntryPayloadJsonCodec} 组合使用；字段集合、写入顺序与拒绝条件保持原样。
 */
final class HistoryTurnStartJsonCodec {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final Set<String> TURN_CAPABILITIES_FIELDS =
      orderedSet("subagentBindings", "mediaCapabilities");
  private static final Set<String> SUBAGENT_BINDING_FIELDS = orderedSet("name", "description");
  private static final Set<String> TURN_MEDIA_CAPABILITIES_FIELDS =
      orderedSet("inputModalities", "userModalities", "toolResultModalities");
  private static final Set<String> COMPACTION_START_FIELDS =
      orderedSet(
          "trigger",
          "execution",
          "outputBudget",
          "cutEntryId",
          "childThreadId",
          "joinInvocationId");

  private HistoryTurnStartJsonCodec() {}

  static ObjectNode encodeTurnCapabilities(TurnCapabilities value) {
    ObjectNode node = NODES.objectNode();
    var subagents = node.putArray("subagentBindings");
    for (SubagentBinding binding : value.subagentBindings()) {
      ObjectNode item = subagents.addObject();
      item.put("name", binding.name());
      item.put("description", binding.description());
    }
    node.set("mediaCapabilities", encodeTurnMediaCapabilities(value.mediaCapabilities()));
    return node;
  }

  static ObjectNode encodeTurnMediaCapabilities(TurnMediaCapabilities value) {
    ObjectNode node = NODES.objectNode();
    node.set("inputModalities", encodeModalities(value.inputModalities()));
    node.set("userModalities", encodeModalities(value.userModalities()));
    node.set("toolResultModalities", encodeModalities(value.toolResultModalities()));
    return node;
  }

  static ArrayNode encodeModalities(Set<ModelInputModality> modalities) {
    ArrayNode array = NODES.arrayNode();
    modalities.stream().map(Enum::name).sorted().forEach(array::add);
    return array;
  }

  static TurnCapabilities decodeTurnCapabilities(JsonNode value) {
    ObjectNode node = object(value, "TURN_START.capabilities");
    JsonCodecSupport.requireFields(node, TURN_CAPABILITIES_FIELDS, "TURN_START.capabilities");
    ArrayNode subagentValues =
        HistoryValueCodecs.requireArray(
            node.get("subagentBindings"), "TURN_START.capabilities.subagentBindings");
    List<SubagentBinding> subagentBindings = new ArrayList<>(subagentValues.size());
    for (JsonNode subagentValue : subagentValues) {
      String context = "TURN_START.capabilities.subagentBindings[]";
      ObjectNode subagent = object(subagentValue, context);
      JsonCodecSupport.requireFields(subagent, SUBAGENT_BINDING_FIELDS, context);
      subagentBindings.add(
          new SubagentBinding(
              HistoryValueCodecs.requiredText(subagent, "name", context),
              HistoryValueCodecs.requiredText(subagent, "description", context)));
    }
    return new TurnCapabilities(
        subagentBindings, decodeTurnMediaCapabilities(node.get("mediaCapabilities")));
  }

  static TurnMediaCapabilities decodeTurnMediaCapabilities(JsonNode value) {
    String context = "TURN_START.capabilities.mediaCapabilities";
    ObjectNode node = object(value, context);
    JsonCodecSupport.requireFields(node, TURN_MEDIA_CAPABILITIES_FIELDS, context);
    return new TurnMediaCapabilities(
        decodeModalities(node.get("inputModalities"), context + ".inputModalities"),
        decodeModalities(node.get("userModalities"), context + ".userModalities"),
        decodeModalities(node.get("toolResultModalities"), context + ".toolResultModalities"));
  }

  static Set<ModelInputModality> decodeModalities(JsonNode value, String context) {
    ArrayNode array = HistoryValueCodecs.requireArray(value, context);
    Set<ModelInputModality> modalities = new LinkedHashSet<>();
    for (JsonNode item : array) {
      if (!item.isString()) {
        throw new IllegalArgumentException(context + "[] must be text");
      }
      ModelInputModality modality;
      try {
        modality = ModelInputModality.valueOf(item.stringValue());
      } catch (IllegalArgumentException error) {
        throw new IllegalArgumentException(context + "[] is not a known modality", error);
      }
      if (!modalities.add(modality)) {
        throw new IllegalArgumentException(context + "[] must not repeat");
      }
    }
    return Set.copyOf(modalities);
  }

  static ObjectNode encodeCompactionStart(CompactionStart start) {
    ObjectNode node = NODES.objectNode();
    node.put("trigger", start.trigger().name());
    if (start.execution() == null) {
      node.putNull("execution");
    } else {
      node.set("execution", ExecutionSelectionJsonCodec.encode(start.execution()));
    }
    if (start.outputBudget() == null) {
      node.putNull("outputBudget");
    } else {
      node.put("outputBudget", start.outputBudget());
    }
    node.put("cutEntryId", start.cutEntryId().toString());
    if (start.childThreadId() == null) {
      node.putNull("childThreadId");
    } else {
      node.put("childThreadId", start.childThreadId().toString());
    }
    if (start.joinInvocationId() == null) {
      node.putNull("joinInvocationId");
    } else {
      node.put("joinInvocationId", start.joinInvocationId().toString());
    }
    return node;
  }

  static CompactionStart decodeNullableCompactionStart(JsonNode value) {
    if (value.isNull()) {
      return null;
    }
    ObjectNode node = object(value, "TURN_START.compaction");
    JsonCodecSupport.requireFields(node, COMPACTION_START_FIELDS, "TURN_START.compaction");
    JsonNode executionNode = node.get("execution");
    ExecutionSelection execution =
        executionNode == null || executionNode.isNull()
            ? null
            : ExecutionSelectionJsonCodec.decode(executionNode, "TURN_START.compaction.execution");
    return new CompactionStart(
        HistoryValueCodecs.readEnum(
            CompactionTrigger.class,
            HistoryValueCodecs.text(node, "trigger"),
            "TURN_START.compaction.trigger"),
        execution,
        HistoryValueCodecs.nullablePositiveLong(node, "outputBudget", "TURN_START.compaction"),
        HistoryValueCodecs.requiredPositiveId(node, "cutEntryId", "TURN_START.compaction"),
        HistoryValueCodecs.nullablePositiveId(node, "childThreadId", "TURN_START.compaction"),
        HistoryValueCodecs.nullablePositiveId(node, "joinInvocationId", "TURN_START.compaction"));
  }
}
