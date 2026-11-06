package fun.fengwk.kkstudio.canvas.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canvas wire DTO 契约：typed command 封闭集合与领域的 {@code CanvasCommand} 一一对应，durable 位置是规范非负十进制字符串，
 * required-nullable 字段显式发射 null。
 */
class CanvasDtoContractTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final String[] REQUIRED_NULLABLE_FIELDS = {
    "CanvasResourceNodeDTO.groupId",
    "CanvasResourceNodeDTO.function",
    "CanvasResourceNodeDTO.run",
    "CanvasResourceDTO.Blob.sizeBytes",
    "CanvasResourceDTO.Blob.width",
    "CanvasResourceDTO.Blob.height",
    "CanvasResourceDTO.Blob.durationMs",
    "CanvasFunctionRunDTO.error",
    "CanvasFunctionDefinitionDTO.unavailableReason"
  };

  /** durable 位置与版本游标在 wire 上永远是十进制字符串，避免 JS 丢精度。 */
  private static final String[] VERSION_STRING_FIELDS = {
    "CanvasDocumentDTO.revision", "CanvasPatchDTO.revision"
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

  /** 多形态 DTO 只有一个判别属性名，前端与错误载荷不按 DTO 猜测不同字段。 */
  @Test
  void polymorphicDtosUseTypeDiscriminator() {
    for (Class<?> type :
        List.of(
            CanvasCommandDTO.class,
            CanvasResourceInputDTO.class,
            CanvasConflictDTO.class,
            CanvasNodePatchDTO.class,
            CanvasGroupPatchDTO.class,
            CanvasResourceDTO.class)) {
      JsonTypeInfo info = type.getAnnotation(JsonTypeInfo.class);
      assertNotNull(info, type.getSimpleName() + " must declare its wire discriminator");
      assertEquals("type", info.property(), type.getSimpleName() + " discriminator");
    }
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
        "command DTO count must match CanvasCommand");
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
            "nodeId", "UUID",
            "expectedName", "String",
            "name", "String"),
        componentTypes(CanvasCommandDTO.RenameNode.class));
    assertEquals(
        Map.of(
            "nodeId", "UUID",
            "expectedGroupId", "UUID",
            "groupId", "UUID"),
        componentTypes(CanvasCommandDTO.SetNodeGroup.class));
    assertEquals(
        Map.of(
            "nodeId", "UUID",
            "transform", "CanvasTransformDTO",
            "expectedTransform", "CanvasTransformDTO"),
        componentTypes(CanvasCommandDTO.UpdateNodeTransform.class));
    assertEquals(
        Map.of(
            "nodeId", "UUID",
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

  /** Function 目录以 name/description/argsSchema 与目录版本为身份。 */
  @Test
  void functionCatalogExposesNameDescriptionAndArgsSchema() {
    assertEquals(
        Map.of(
            "name", "String",
            "description", "String",
            "argsSchema", "Map",
            "outputs", "List",
            "referencePolicy", "CanvasFunctionReferencePolicyDTO",
            "available", "Boolean",
            "unavailableReason", "String",
            "version", "CanvasFunctionVersionDTO"),
        componentTypes(CanvasFunctionDefinitionDTO.class));
    assertEquals(
        Map.of("definitionFingerprint", "String"), componentTypes(CanvasFunctionVersionDTO.class));
    assertEquals(
        Map.of("kind", "CanvasResourceKind", "name", "String"),
        componentTypes(CanvasFunctionOutputDTO.class));
  }

  /** start 必带 expectedVersion；cancel 是独立的 requestId-only wire，不留下非必填版本分支。 */
  @Test
  void startRequiresExpectedVersionWhileCancelStaysRequestIdOnly() {
    assertEquals(
        Map.of("requestId", "String", "expectedVersion", "CanvasFunctionVersionDTO"),
        componentTypes(CanvasFunctionRunRequestDTO.class));
    assertEquals(
        Map.of("requestId", "String"), componentTypes(CanvasFunctionCancelRequestDTO.class));
  }

  /** Function 只有 name 与自由 args，目录不假设 model/prompt/provider。 */
  @Test
  void functionExposesNameAndArgsOnly() {
    assertEquals(Map.of("name", "String", "args", "Map"), componentTypes(CanvasFunctionDTO.class));
  }

  @Test
  void snapshotCarriesDocumentNodesGroupsAndReferences() {
    assertEquals(
        Map.of(
            "document", "CanvasDocumentDTO",
            "nodes", "List",
            "groups", "List",
            "references", "List"),
        componentTypes(CanvasSnapshotDTO.class));
  }

  /** 冲突载荷必须描述受影响对象与服务端权威值，判别值由类型信息给出。 */
  @Test
  void conflictsCarryTargetAndServerValues() {
    JsonSubTypes subtypes = CanvasConflictDTO.class.getAnnotation(JsonSubTypes.class);
    assertNotNull(subtypes, "CanvasConflictDTO must declare its wire discriminators");
    Map<String, String> actual = new LinkedHashMap<>();
    for (JsonSubTypes.Type type : subtypes.value()) {
      actual.put(type.value().getSimpleName(), type.name());
    }
    assertEquals(
        Map.of(
            "TargetMissing", "TARGET_MISSING",
            "TargetPresent", "TARGET_PRESENT",
            "StaleNode", "STALE_NODE",
            "StaleGroup", "STALE_GROUP",
            "NodeRunning", "NODE_RUNNING",
            "NodeReferenced", "NODE_REFERENCED"),
        actual);
    assertEquals(
        Map.of("nodeId", "String", "group", "NodeGroup", "current", "CanvasResourceNodeDTO"),
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

  private static Map<String, String> componentTypes(Class<?> type) {
    Map<String, String> components = new LinkedHashMap<>();
    if (type.isRecord()) {
      for (RecordComponent component : type.getRecordComponents()) {
        components.put(component.getName(), component.getType().getSimpleName());
      }
    } else {
      for (Field field : type.getDeclaredFields()) {
        if (!Modifier.isStatic(field.getModifiers())) {
          components.put(field.getName(), field.getType().getSimpleName());
        }
      }
    }
    return components;
  }

  private static Field field(String ownerAndField) throws NoSuchFieldException {
    int split = ownerAndField.lastIndexOf('.');
    String type = ownerAndField.substring(0, split).replace('.', '$');
    String name = ownerAndField.substring(split + 1);
    String owner = "fun.fengwk.kkstudio.canvas.dto." + type;
    try {
      return Class.forName(owner).getDeclaredField(name);
    } catch (ClassNotFoundException error) {
      throw new AssertionError("unknown DTO: " + owner, error);
    }
  }
}
