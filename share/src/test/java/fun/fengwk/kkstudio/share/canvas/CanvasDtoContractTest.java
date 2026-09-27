package fun.fengwk.kkstudio.share.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canvas wire DTO 契约：typed command 封闭集合与 canvas-core 的 {@code CanvasCommand} 一一对应，durable
 * 位置是规范非负十进制字符串， required-nullable 字段显式发射 null。
 */
class CanvasDtoContractTest {

  private static final String[] REQUIRED_NULLABLE_FIELDS = {
    "CanvasResourceNodeDTO.groupId",
    "CanvasResourceNodeDTO.function",
    "CanvasResourceNodeDTO.run",
    "CanvasResourceDTO.blobId",
    "CanvasResourceDTO.textContent",
    "CanvasResourceDTO.mediaType",
    "CanvasResourceDTO.sizeBytes",
    "CanvasResourceDTO.width",
    "CanvasResourceDTO.height",
    "CanvasResourceDTO.durationMs",
    "CanvasFunctionRunDTO.error",
    "CanvasFunctionModelDTO.unavailableReason",
    "CanvasFunctionParameterDefinitionDTO.defaultValue",
    "CanvasFunctionParameterDefinitionDTO.min",
    "CanvasFunctionParameterDefinitionDTO.max"
  };

  /** durable 位置与版本游标在 wire 上永远是十进制字符串，避免 JS 丢精度。 */
  private static final String[] VERSION_STRING_FIELDS = {
    "CanvasDocumentDTO.revision", "CanvasPatchDTO.revision", "CanvasVersionEventDTO.revision"
  };

  /** typed command 的 wire 名与核心命令一一对应；缺失或改名都会让前端错位。 */
  private static final Map<String, String> COMMAND_WIRE_NAMES = new LinkedHashMap<>();

  static {
    COMMAND_WIRE_NAMES.put("CreateNode", "CREATE_NODE");
    COMMAND_WIRE_NAMES.put("RenameNode", "RENAME_NODE");
    COMMAND_WIRE_NAMES.put("SetNodeResources", "SET_NODE_RESOURCES");
    COMMAND_WIRE_NAMES.put("SetNodeFunction", "SET_NODE_FUNCTION");
    COMMAND_WIRE_NAMES.put("SetNodeGroup", "SET_NODE_GROUP");
    COMMAND_WIRE_NAMES.put("DeleteNode", "DELETE_NODE");
    COMMAND_WIRE_NAMES.put("UpdateNodeTransform", "UPDATE_NODE_TRANSFORM");
    COMMAND_WIRE_NAMES.put("CreateGroup", "CREATE_GROUP");
    COMMAND_WIRE_NAMES.put("RenameGroup", "RENAME_GROUP");
    COMMAND_WIRE_NAMES.put("UpdateGroupTransform", "UPDATE_GROUP_TRANSFORM");
    COMMAND_WIRE_NAMES.put("DeleteGroup", "DELETE_GROUP");
  }

  @Test
  void durableRevisionsAreWireDecimalStrings() throws Exception {
    for (String field : VERSION_STRING_FIELDS) {
      Field version = field(field);
      assertEquals(
          String.class,
          version.getType(),
          field + " must be String so the wire is always a decimal string");
    }
  }

  @Test
  void typedCommandsMatchCoreCommandSetExactly() {
    assertEquals(
        COMMAND_WIRE_NAMES.size(),
        CanvasCommandDTO.class.getPermittedSubclasses().length,
        "command DTO count must match canvas-core CanvasCommand");
    JsonSubTypes subtypes = CanvasCommandDTO.class.getAnnotation(JsonSubTypes.class);
    assertNotNull(subtypes, "CanvasCommandDTO must declare its wire discriminators");
    Map<String, String> actual = new LinkedHashMap<>();
    for (JsonSubTypes.Type type : subtypes.value()) {
      actual.put(type.value().getSimpleName(), type.name());
    }
    assertEquals(COMMAND_WIRE_NAMES, actual);
  }

  /** 编辑前置条件是各语义组的编辑起点旧值，因此命令必须携带旧名称、旧资源数组、旧 Function 或旧几何。 */
  @Test
  void commandsCarryGroupScopedPreconditionsInsteadOfGraphVersion() {
    assertEquals(
        Map.of(
            "nodeId", "String",
            "expectedName", "String",
            "name", "String"),
        componentTypes(CanvasCommandDTO.RenameNode.class));
    assertEquals(
        Map.of(
            "nodeId", "String",
            "expectedGroupId", "String",
            "groupId", "String"),
        componentTypes(CanvasCommandDTO.SetNodeGroup.class));
    assertEquals(
        Map.of(
            "nodeId", "String",
            "transform", "CanvasTransformDTO",
            "expectedTransform", "CanvasTransformDTO"),
        componentTypes(CanvasCommandDTO.UpdateNodeTransform.class));
    assertEquals(
        Map.of(
            "nodeId", "String",
            "expectedResourceIds", "List",
            "expectedFunction", "CanvasFunctionDTO"),
        componentTypes(CanvasCommandDTO.DeleteNode.class));
  }

  /** 资源槽位意图只有三种：保留既有行、新文本行、新 blob 行。 */
  @Test
  void resourceInputsExposeKeepTextAndBlobOnly() {
    JsonSubTypes subtypes = CanvasResourceInputDTO.class.getAnnotation(JsonSubTypes.class);
    assertNotNull(subtypes, "CanvasResourceInputDTO must declare its wire discriminators");
    Map<String, String> actual = new LinkedHashMap<>();
    for (JsonSubTypes.Type type : subtypes.value()) {
      actual.put(type.value().getSimpleName(), type.name());
    }
    assertEquals(Map.of("Keep", "KEEP", "Text", "TEXT", "Blob", "BLOB"), actual);
  }

  /** Function 只有 name 与自由 args，目录不假设 model/prompt/provider。 */
  @Test
  void functionExposesNameAndArgsOnly() {
    assertEquals(Map.of("name", "String", "args", "Map"), componentTypes(CanvasFunctionDTO.class));
  }

  /** 整图 CAS 游标与可写 Link 模型已删除，不再保留兼容字段或兼容 DTO。 */
  @Test
  void graphCasCursorAndWriteLinkModelAreRemoved() {
    assertThrows(
        NoSuchFieldException.class,
        () -> ApplyCanvasCommandsRequestDTO.class.getDeclaredField("expectedVersion"),
        "typed commands replace the whole-graph expectedVersion CAS cursor");
    assertThrows(
        NoSuchFieldException.class,
        () -> CanvasPatchDTO.class.getDeclaredField("baseVersion"),
        "patch is a single accepted revision, not a baseVersion -> version pair");
    assertThrows(
        NoSuchFieldException.class,
        () -> CanvasPatchDTO.class.getDeclaredField("links"),
        "links are projected from Function args and are never written");
    assertThrows(
        NoSuchFieldException.class,
        () -> CanvasSnapshotDTO.class.getDeclaredField("links"),
        "snapshot exposes derived references instead of writable links");
    assertTrue(
        CanvasSnapshotDTO.class.getDeclaredFields().length == 4,
        "snapshot carries document, nodes, groups and derived references");
    for (String removed : new String[] {"CanvasLinkDTO", "CanvasLinkPatchDTO"}) {
      assertThrows(
          ClassNotFoundException.class,
          () -> Class.forName("fun.fengwk.kkstudio.share.canvas." + removed),
          removed + " must not survive as a compatibility model");
    }
  }

  /** 冲突载荷必须描述受影响对象与服务端权威值。 */
  @Test
  void conflictsCarryTargetAndServerValues() {
    assertEquals(
        Map.of(
            CanvasConflictDTO.TargetMissing.class, "TARGET_MISSING",
            CanvasConflictDTO.TargetPresent.class, "TARGET_PRESENT",
            CanvasConflictDTO.StaleNode.class, "STALE_NODE",
            CanvasConflictDTO.StaleGroup.class, "STALE_GROUP",
            CanvasConflictDTO.NodeRunning.class, "NODE_RUNNING",
            CanvasConflictDTO.NodeReferenced.class, "NODE_REFERENCED"),
        Map.of(
            CanvasConflictDTO.TargetMissing.class,
            new CanvasConflictDTO.TargetMissing("t", "NODE").kind(),
            CanvasConflictDTO.TargetPresent.class,
            new CanvasConflictDTO.TargetPresent("t", "NODE").kind(),
            CanvasConflictDTO.StaleNode.class,
            new CanvasConflictDTO.StaleNode("n", "NAME", null).kind(),
            CanvasConflictDTO.StaleGroup.class,
            new CanvasConflictDTO.StaleGroup("g", null).kind(),
            CanvasConflictDTO.NodeRunning.class,
            new CanvasConflictDTO.NodeRunning("n", null).kind(),
            CanvasConflictDTO.NodeReferenced.class,
            new CanvasConflictDTO.NodeReferenced("n", List.of("r")).kind()));
    assertEquals(
        Map.of("nodeId", "String", "group", "String", "current", "CanvasResourceNodeDTO"),
        componentTypes(CanvasConflictDTO.StaleNode.class));
  }

  @Test
  void requiredNullableFieldsAreAlwaysIncluded() throws Exception {
    for (String field : REQUIRED_NULLABLE_FIELDS) {
      Field nullable = field(field);
      JsonInclude include = nullable.getAnnotation(JsonInclude.class);
      assertNotNull(include, field + " must declare @JsonInclude");
      assertEquals(
          JsonInclude.Include.ALWAYS,
          include.value(),
          field + " must explicitly emit null even under the global NON_NULL default");
    }
  }

  @Test
  void canvasDocumentDoesNotExposeThreadBinding() {
    assertThrows(
        NoSuchFieldException.class,
        () -> CanvasDocumentDTO.class.getDeclaredField("threadId"),
        "Canvas document ownership is represented by session_owner relations");
  }

  private static Map<String, String> componentTypes(Class<?> record) {
    Map<String, String> components = new LinkedHashMap<>();
    for (RecordComponent component : record.getRecordComponents()) {
      components.put(component.getName(), component.getType().getSimpleName());
    }
    return components;
  }

  private static Field field(String ownerAndField) throws NoSuchFieldException {
    String[] parts = ownerAndField.split("\\.");
    String owner = "fun.fengwk.kkstudio.share.canvas." + parts[0];
    try {
      return Class.forName(owner).getDeclaredField(parts[1]);
    } catch (ClassNotFoundException error) {
      throw new AssertionError("unknown DTO: " + owner, error);
    }
  }
}
