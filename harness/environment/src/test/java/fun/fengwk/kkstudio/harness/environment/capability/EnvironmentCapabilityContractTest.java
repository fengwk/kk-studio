package fun.fengwk.kkstudio.harness.environment.capability;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.result.ResultContent;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.common.schema.IntegerSchema;
import fun.fengwk.kkstudio.common.schema.StringSchema;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Environment Capability descriptor、参数、请求、结果与规范校验契约测试。 */
class EnvironmentCapabilityContractTest {

  private static final InputSchema SCHEMA =
      new InputSchema(
          null,
          Map.of("path", new StringSchema(null), "offset", new IntegerSchema(null)),
          Set.of("path"),
          false);

  private static final EnvironmentCapabilityDescriptor DESCRIPTOR =
      new EnvironmentCapabilityDescriptor(
          new EnvironmentCapabilityId("fs.read"), SCHEMA, Duration.ofSeconds(10));

  /** 反射锁定 capability descriptor 只有执行所需字段，不引入模型展示、renderer 或权限元数据。 */
  @Test
  void descriptorContainsOnlyCapabilityFields() {
    RecordComponent[] components = EnvironmentCapabilityDescriptor.class.getRecordComponents();

    assertArrayEquals(
        new String[] {"id", "inputSchema", "defaultTimeout"}, componentNames(components));
    assertEquals(EnvironmentCapabilityId.class, components[0].getType());
    assertEquals(InputSchema.class, components[1].getType());
    assertEquals(Duration.class, components[2].getType());
  }

  /** 文件前置条件属于执行元数据；不把 workdir 或模型参数提升为第二份执行状态。 */
  @Test
  void requestContainsOnlyCapabilityExecutionFields() {
    assertArrayEquals(
        new String[] {"descriptor", "call", "timeout", "expectedFile", "resourceDownload"},
        componentNames(EnvironmentCapabilityExecutionRequest.class.getRecordComponents()));
  }

  /** defaultTimeout 允许 zero 但不能为负。 */
  @Test
  void validatesDescriptorBounds() {
    assertEquals(
        Duration.ZERO,
        new EnvironmentCapabilityDescriptor(
                new EnvironmentCapabilityId("fs.read"), SCHEMA, Duration.ZERO)
            .defaultTimeout());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EnvironmentCapabilityDescriptor(
                new EnvironmentCapabilityId("fs.read"), SCHEMA, Duration.ofSeconds(-1)));
  }

  /** 数字字符串经过归一化器改为 JSON integer，且原调用保持不变。 */
  @Test
  void normalizesCapabilityCallArguments() {
    EnvironmentCapabilityCall original =
        new EnvironmentCapabilityCall("call-1", "{\"offset\":\"10\",\"path\":\"/srv/README.md\"}");

    EnvironmentCapabilityCall normalized = original.validateFor(DESCRIPTOR);

    assertEquals("{\"offset\":\"10\",\"path\":\"/srv/README.md\"}", original.argumentsJson());
    assertEquals("{\"offset\":10,\"path\":\"/srv/README.md\"}", normalized.argumentsJson());
    assertEquals("call-1", normalized.id());
    assertNotSame(original, normalized);
  }

  /** strict Provider 对原可选 offset 回传 null 时，Environment 执行边界同样把它等价为缺省。 */
  @Test
  void removesOptionalNullFromCapabilityCall() {
    EnvironmentCapabilityCall original =
        new EnvironmentCapabilityCall("call-1", "{\"offset\":null,\"path\":\"/srv/README.md\"}");

    EnvironmentCapabilityCall normalized = original.validateFor(DESCRIPTOR);

    assertEquals("{\"path\":\"/srv/README.md\"}", normalized.argumentsJson());
    assertEquals("{\"offset\":null,\"path\":\"/srv/README.md\"}", original.argumentsJson());
  }

  /** 数字字符串经过现有归一化器改为 JSON integer，归一化后仍需通过 schema 校验。 */
  @Test
  void normalizesAndValidatesNumericArguments() {
    EnvironmentCapabilityCall normalized =
        new EnvironmentCapabilityCall("call-1", "{\"offset\":\"10\",\"path\":\"/srv/README.md\"}")
            .validateFor(DESCRIPTOR);

    assertEquals("{\"offset\":10,\"path\":\"/srv/README.md\"}", normalized.argumentsJson());
  }

  /** 调用 id 必须非空，参数必须是 JSON object，且 schema 错误不能被归一化吞掉。 */
  @Test
  void rejectsInvalidCapabilityCalls() {
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentCapabilityCall(" ", "{}"));
    assertThrows(
        IllegalArgumentException.class, () -> new EnvironmentCapabilityCall("call-1", "[]"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EnvironmentCapabilityCall("call-1", "{\"offset\":\"bad\"}")
                .validateFor(DESCRIPTOR));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EnvironmentCapabilityCall("call-1", "{\"extra\":true,\"path\":\"/srv/a\"}")
                .validateFor(DESCRIPTOR));
  }

  /** 请求构造时完成参数校验，并原样保留调用方给出的 timeout（0 表示无 deadline），不做任何回落。 */
  @Test
  void requestNormalizesCallAndKeepsGivenTimeout() {
    EnvironmentCapabilityExecutionRequest request =
        new EnvironmentCapabilityExecutionRequest(
            DESCRIPTOR,
            new EnvironmentCapabilityCall(
                "call-1", "{\"offset\":\"20\",\"path\":\"/srv/README.md\"}"),
            Duration.ZERO);

    assertEquals("{\"offset\":20,\"path\":\"/srv/README.md\"}", request.call().argumentsJson());
    assertEquals(Duration.ZERO, request.timeout());
  }

  /** timeout 不能为负；请求外壳不携带 workdir（目录只存在于 arguments）。 */
  @Test
  void validatesRequestTimeoutAndCarriesNoWorkdir() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EnvironmentCapabilityExecutionRequest(
                DESCRIPTOR,
                new EnvironmentCapabilityCall("call-1", "{\"path\":\"/srv/a\"}"),
                Duration.ofSeconds(-1)));
    assertEquals(
        List.of("descriptor", "call", "timeout", "expectedFile", "resourceDownload"),
        Arrays.stream(EnvironmentCapabilityExecutionRequest.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList());
  }

  /** 新文件的 fs.write 前置条件不带 SHA；edit 仍必须引用现有文件的内容哈希。 */
  @Test
  void expectedFileAllowsMissingShaOnlyForWrite() {
    EnvironmentCapabilityDescriptor write =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_WRITE);
    EnvironmentCapabilityDescriptor edit =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_EDIT);
    ExpectedFile newFile = new ExpectedFile("/srv/new.txt", null);

    EnvironmentCapabilityExecutionRequest request =
        new EnvironmentCapabilityExecutionRequest(
            write,
            new EnvironmentCapabilityCall(
                "write-call", "{\"path\":\"/srv/new.txt\",\"content\":\"x\"}"),
            Duration.ZERO,
            newFile);

    assertEquals(newFile, request.expectedFile());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EnvironmentCapabilityExecutionRequest(
                edit,
                new EnvironmentCapabilityCall(
                    "edit-call",
                    "{\"path\":\"/srv/a.txt\",\"edits\":[{\"old_string\":\"a\",\"new_string\":\"b\"}]}"),
                Duration.ZERO,
                newFile));
  }

  /** Capability result 的 details JSON 默认值、object 校验和 content 数量边界。 */
  @Test
  void enforcesCapabilityResultBoundaries() {
    assertEquals(64, EnvironmentCapabilityResult.MAX_CONTENT_ITEMS);
    assertEquals(1024 * 1024, EnvironmentCapabilityResult.MAX_DETAILS_JSON_UTF8_BYTES);
    assertEquals(
        "{}", new EnvironmentCapabilityResult("call-1", List.of(), false, " ").detailsJson());
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentCapabilityResult("call-1", List.of(), false, "[]"));

    List<ResultContent> atLimit = new ArrayList<>();
    for (int index = 0; index < EnvironmentCapabilityResult.MAX_CONTENT_ITEMS; index++) {
      atLimit.add(new TextResultContent("x"));
    }
    assertEquals(
        EnvironmentCapabilityResult.MAX_CONTENT_ITEMS,
        new EnvironmentCapabilityResult("call-1", atLimit, false, "{}").contents().size());

    List<ResultContent> overLimit = new ArrayList<>(atLimit);
    overLimit.add(new TextResultContent("x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentCapabilityResult("call-1", overLimit, false, "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentCapabilityResult(" ", List.of(), false, "{}"));
    assertThrows(
        NullPointerException.class,
        () -> new EnvironmentCapabilityResult("call-1", null, false, "{}"));
  }

  /** details JSON 的原始 UTF-8 上限校验。 */
  @Test
  void enforcesCapabilityResultDetailsByteLimit() {
    String atLimit =
        "{\"d\":\""
            + "a".repeat(EnvironmentCapabilityResult.MAX_DETAILS_JSON_UTF8_BYTES - 8)
            + "\"}";
    assertEquals(
        atLimit,
        new EnvironmentCapabilityResult("call-1", List.of(), false, atLimit).detailsJson());

    String overLimit =
        "{\"d\":\""
            + "a".repeat(EnvironmentCapabilityResult.MAX_DETAILS_JSON_UTF8_BYTES - 7)
            + "\"}";
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentCapabilityResult("call-1", List.of(), false, overLimit));
  }

  private static String[] componentNames(RecordComponent[] components) {
    String[] names = new String[components.length];
    for (int index = 0; index < components.length; index++) {
      names[index] = components[index].getName();
    }
    return names;
  }
}
