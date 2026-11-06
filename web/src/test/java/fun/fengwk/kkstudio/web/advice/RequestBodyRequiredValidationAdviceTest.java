package fun.fengwk.kkstudio.web.advice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.canvas.dto.ApplyCanvasCommandsRequestDTO;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginConfigurationUpdateRequestDTO;
import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;

import java.util.List;
import java.util.Map;

/**
 * 验证 {@link RequestBodyRequiredValidationAdvice} 在真实 HTTP MockMvc 管道中的必填属性校验行为。
 *
 * <p>覆盖用例：
 *
 * <ul>
 *   <li>缺失与显式 null 必填属性校验。
 *   <li>原始 JSON 树检测 primitive 缺失与默认值缺失。
 *   <li>{@link Nullable} 属性省略与显式 null。
 *   <li>嵌套 DTO、集合元素、Map 键值的递归校验。
 *   <li>开放 JSON Map（{@code Map<String, Object>}）允许其值为 null，且不触发 DTO 自省。
 *   <li>多态（{@link JsonTypeInfo}）与继承类的属性继承自省。
 *   <li>Java Record 的组件必填性与可空性。
 * </ul>
 */
class RequestBodyRequiredValidationAdviceTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    JsonMapper mapper = StrictHttpJsonMapperTestSupport.get();
    RequestBodyRequiredValidationAdvice advice = new RequestBodyRequiredValidationAdvice(mapper);
    TestProbeController controller = new TestProbeController();
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(
                new JacksonJsonHttpMessageConverter(StrictHttpJsonMapperTestSupport.strictHttp()))
            .setControllerAdvice(advice)
            .build();
  }

  @Test
  void rootRequiredPropertyMissingThrowsBadRequest() throws Exception {
    MvcResult result =
        mockMvc
            .perform(post("/test/basic").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andReturn();

    assertBadRequestDetail(result, "name is required");
  }

  @Test
  void rootRequiredPropertyExplicitNullThrowsBadRequest() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/test/basic")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\": null}"))
            .andExpect(status().isBadRequest())
            .andReturn();

    assertBadRequestDetail(result, "name is required");
  }

  @Test
  void rootRequiredPropertyPresentSucceeds() throws Exception {
    mockMvc
        .perform(
            post("/test/basic")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Alice\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void primitiveMissingInJsonThrowsBadRequest() throws Exception {
    // 客户端未传 count，虽然 Java 反序列化后默认值为 0，但原始 JSON 缺失，必须抛出异常
    MvcResult result =
        mockMvc
            .perform(post("/test/primitive").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andReturn();

    assertBadRequestDetail(result, "count is required");
  }

  @Test
  void primitiveBooleanMissingInJsonThrowsBadRequest() throws Exception {
    // 传了 count 但漏传 active（primitive boolean）
    MvcResult result =
        mockMvc
            .perform(
                post("/test/primitive")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"count\": 0}"))
            .andExpect(status().isBadRequest())
            .andReturn();

    assertBadRequestDetail(result, "active is required");
  }

  @Test
  void primitiveNullReportsTheRequiredPath() throws Exception {
    // Jackson 的 primitive null 错误也须给出属性路径。
    MvcResult result =
        mockMvc
            .perform(
                post("/test/primitive")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"count\":null,\"active\":false}"))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertBadRequestDetail(result, "count is required");
  }

  @Test
  void primitivePresentWithFalsyValuesSucceeds() throws Exception {
    // 传了 count: 0 与 active: false，合法存在，校验通过
    mockMvc
        .perform(
            post("/test/primitive")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"count\": 0, \"active\": false}"))
        .andExpect(status().isOk());
  }

  @Test
  void defaultValuePropertyMissingInJsonThrowsBadRequest() throws Exception {
    // Java 类字段有默认值 "ACTIVE"，但客户端未传该必填字段，必须抛出缺失异常
    MvcResult result =
        mockMvc
            .perform(
                post("/test/default-value").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andReturn();

    assertBadRequestDetail(result, "status is required");
  }

  @Test
  void defaultValuePropertyPresentSucceeds() throws Exception {
    mockMvc
        .perform(
            post("/test/default-value")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"ACTIVE\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void nullablePropertyCanBeOmittedOrNull() throws Exception {
    // 标有 @Nullable 的字段可以省略
    mockMvc
        .perform(
            post("/test/nullable")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requiredTitle\": \"Doc\"}"))
        .andExpect(status().isOk());

    // 标有 @Nullable 的字段可以显式为 null
    mockMvc
        .perform(
            post("/test/nullable")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requiredTitle\": \"Doc\", \"optionalDescription\": null}"))
        .andExpect(status().isOk());
  }

  @Test
  void nestedDtoMissingOrNullThrowsBadRequest() throws Exception {
    MvcResult missingResult =
        mockMvc
            .perform(post("/test/nested").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertBadRequestDetail(missingResult, "address is required");

    MvcResult nullResult =
        mockMvc
            .perform(
                post("/test/nested")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"address\": null}"))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertBadRequestDetail(nullResult, "address is required");
  }

  @Test
  void nestedDtoInnerPropertyMissingThrowsBadRequest() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/test/nested")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"address\": {}}"))
            .andExpect(status().isBadRequest())
            .andReturn();

    assertBadRequestDetail(result, "address.city is required");
  }

  @Test
  void optionalNestedDtoNullSucceedsButInnerPropertyMissingThrowsBadRequest() throws Exception {
    // 可选的嵌套 DTO 为 null 时通过
    mockMvc
        .perform(
            post("/test/nested")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"address\": {\"city\": \"Beijing\"}, \"optionalBackup\": null}"))
        .andExpect(status().isOk());

    // 可选的嵌套 DTO 提供了实体对象时，其内部必填字段缺失必须报错
    MvcResult result =
        mockMvc
            .perform(
                post("/test/nested")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"address\": {\"city\": \"Beijing\"}, \"optionalBackup\": {}}"))
            .andExpect(status().isBadRequest())
            .andReturn();

    assertBadRequestDetail(result, "optionalBackup.city is required");
  }

  @Test
  void listRequiredAndElementsMissingThrowsBadRequest() throws Exception {
    // 列表字段缺失
    MvcResult missingList =
        mockMvc
            .perform(post("/test/list").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertBadRequestDetail(missingList, "items is required");

    // 列表元素为 null
    MvcResult nullElement =
        mockMvc
            .perform(
                post("/test/list")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"items\": [null]}"))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertBadRequestDetail(nullElement, "items[0] is required");

    // 列表元素内部必填属性缺失
    MvcResult missingElementProp =
        mockMvc
            .perform(
                post("/test/list")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"items\": [{\"code\": \"A\"}, {}]}"))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertBadRequestDetail(missingElementProp, "items[1].code is required");
  }

  @Test
  void scalarListWithNullElementThrowsBadRequest() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/test/scalar-list")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"tags\": [\"alpha\", null]}"))
            .andExpect(status().isBadRequest())
            .andReturn();

    assertBadRequestDetail(result, "tags[1] is required");
  }

  @Test
  void scalarListWithNullableElementAnnotationAllowsNull() throws Exception {
    mockMvc
        .perform(
            post("/test/nullable-scalar-list")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tags\": [\"alpha\", null, \"beta\"]}"))
        .andExpect(status().isOk());
  }

  @Test
  void typedMapValueMissingOrInnerMissingThrowsBadRequest() throws Exception {
    // 类型化 Map 的 value 为 null
    MvcResult nullValueResult =
        mockMvc
            .perform(
                post("/test/typed-map")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"itemMap\": {\"first\": null}}"))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertBadRequestDetail(nullValueResult, "itemMap.first is required");

    // 类型化 Map 的 value 内部必填字段缺失
    MvcResult innerMissingResult =
        mockMvc
            .perform(
                post("/test/typed-map")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"itemMap\": {\"first\": {}}}"))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertBadRequestDetail(innerMissingResult, "itemMap.first.code is required");

    // 合法数据成功
    mockMvc
        .perform(
            post("/test/typed-map")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemMap\": {\"first\": {\"code\": \"OK\"}}}"))
        .andExpect(status().isOk());
  }

  @Test
  void nestedMapsAndNullableMapValuesUseTheirElementAnnotations() throws Exception {
    // 值的 @Nullable 与深层集合里的必填元素不能互相覆盖。
    mockMvc
        .perform(
            post("/test/nested-map")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":{\"optional\":null,\"present\":[{\"code\":\"ok\"}]}}"))
        .andExpect(status().isOk());
    MvcResult missing =
        mockMvc
            .perform(
                post("/test/nested-map")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"items\":{\"present\":[null]}}"))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertBadRequestDetail(missing, "items.present[0] is required");
  }

  @Test
  void openJsonMapAllowsNullAndAvoidsDtoIntrospection() throws Exception {
    // 必填开放 Map 缺失
    MvcResult missingResult =
        mockMvc
            .perform(post("/test/open-map").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertBadRequestDetail(missingResult, "extra is required");

    // 开放 Map 内可包含任何合法 JSON（包含 null、嵌套 Map/List、标量），均不会做 DTO 自省报错
    mockMvc
        .perform(
            post("/test/open-map")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "extra": {
                        "nullField": null,
                        "num": 42,
                        "nested": {
                          "deepNull": null
                        },
                        "list": [1, null, "x"]
                      }
                    }
                    """))
        .andExpect(status().isOk());
  }

  @Test
  void rootOpenJsonMapAllowsAnyJsonPayload() throws Exception {
    mockMvc
        .perform(
            post("/test/root-open-map")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"field\": null, \"child\": {\"key\": null}}"))
        .andExpect(status().isOk());
  }

  @Test
  void polymorphicSubtypeValidationSucceedsAndFailsOnMissingProperties() throws Exception {
    // 缺少子类特有必填字段
    MvcResult missingSubResult =
        mockMvc
            .perform(
                post("/test/polymorphic")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"type\": \"dog\"}"))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertBadRequestDetail(missingSubResult, "dogName is required");

    // 子类字段完整时成功
    mockMvc
        .perform(
            post("/test/polymorphic")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\": \"dog\", \"dogName\": \"Buddy\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void polymorphicRecordWithNestedPrimitiveReportsTheFullPath() throws Exception {
    // Canvas 的命令子类型和几何 record 在反序列化前必须保留精确路径。
    String body =
        "{\"idempotencyKey\":\"id\",\"commands\":[{\"type\":\"CREATE_NODE\","
            + "\"nodeId\":\"node\",\"name\":\"n\",\"resources\":[],"
            + "\"transform\":{\"x\":0,\"y\":0,\"width\":10}}]}";
    MvcResult result =
        mockMvc
            .perform(post("/test/canvas").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertBadRequestDetail(result, "commands[0].transform.height is required");
  }

  @Test
  void nestedResourceDiscriminatorUsesItsOwnSubtypeAndPath() throws Exception {
    // 资源槽位与命令各自是独立的 NAME union，两级都用 type 判别。
    String prefix =
        "{\"idempotencyKey\":\"00000000-0000-0000-0000-000000000001\",\"commands\":[{\"type\":\"CREATE_NODE\","
            + "\"nodeId\":\"00000000-0000-0000-0000-000000000002\",\"name\":\"n\","
            + "\"transform\":{\"x\":0,\"y\":0,\"width\":10,\"height\":10},\"resources\":[";
    String suffix = "]}]}";
    for (var entry :
        Map.of(
                "{\"type\":\"KEEP\"}", "resourceId",
                "{\"type\":\"TEXT\",\"name\":\"n\"}", "textContent",
                "{\"type\":\"BLOB\",\"name\":\"n\"}", "blobId")
            .entrySet()) {
      MvcResult result =
          mockMvc
              .perform(
                  post("/test/canvas")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(prefix + entry.getKey() + suffix))
              .andExpect(status().isBadRequest())
              .andReturn();
      assertBadRequestDetail(
          result, "commands[0].resources[0]." + entry.getValue() + " is required");
    }
    mockMvc
        .perform(
            post("/test/canvas")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    prefix
                        + "{\"type\":\"TEXT\",\"name\":\"n\",\"textContent\":\"body\"}"
                        + suffix))
        .andExpect(status().isOk());
  }

  @Test
  void everyCanvasCommandSubtypeValidatesItsOwnRequiredField() throws Exception {
    // type 的 11 个命令均须在构造 record 前产生各自路径。
    for (String type :
        List.of(
            "CREATE_NODE",
            "RENAME_NODE",
            "SET_NODE_RESOURCES",
            "SET_NODE_FUNCTION",
            "SET_NODE_GROUP",
            "DELETE_NODE",
            "UPDATE_NODE_TRANSFORM",
            "CREATE_GROUP",
            "RENAME_GROUP",
            "UPDATE_GROUP_TRANSFORM",
            "DELETE_GROUP")) {
      String field =
          type.contains("GROUP") && !type.equals("SET_NODE_GROUP") ? "groupId" : "nodeId";
      MvcResult result =
          mockMvc
              .perform(
                  post("/test/canvas")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          "{\"idempotencyKey\":\"id\",\"commands\":[{\"type\":\"" + type + "\"}]}"))
              .andExpect(status().isBadRequest())
              .andReturn();
      assertBadRequestDetail(result, "commands[0]." + field + " is required");
    }
    // 可空子类型字段允许缺失及显式 null。
    for (String command :
        List.of(
            "{\"type\":\"SET_NODE_FUNCTION\",\"nodeId\":\"00000000-0000-0000-0000-000000000002\"}",
            "{\"type\":\"SET_NODE_FUNCTION\",\"nodeId\":\"00000000-0000-0000-0000-000000000002\",\"function\":null}")) {
      mockMvc
          .perform(
              post("/test/canvas")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"idempotencyKey\":\"00000000-0000-0000-0000-000000000001\",\"commands\":["
                          + command
                          + "]}"))
          .andExpect(status().isOk());
    }
  }

  @Test
  void inheritanceSubclassValidatesBothSuperAndSubProperties() throws Exception {
    // 缺少父类必填属性
    MvcResult superMissing =
        mockMvc
            .perform(
                post("/test/inheritance")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"subProperty\": \"sub\"}"))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertBadRequestDetail(superMissing, "superProperty is required");

    // 缺少子类必填属性
    MvcResult subMissing =
        mockMvc
            .perform(
                post("/test/inheritance")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"superProperty\": \"super\"}"))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertBadRequestDetail(subMissing, "subProperty is required");

    // 均存在时成功
    mockMvc
        .perform(
            post("/test/inheritance")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"superProperty\": \"super\", \"subProperty\": \"sub\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void javaRecordValidationRespectsNullableAndRequiredComponents() throws Exception {
    // 缺少必填 record component
    MvcResult missingIdResult =
        mockMvc
            .perform(
                post("/test/record")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"level\": 1}"))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertBadRequestDetail(missingIdResult, "id is required");

    // 缺少 primitive record component
    MvcResult missingPrimitiveResult =
        mockMvc
            .perform(
                post("/test/record")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"id\": \"rec-1\"}"))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertBadRequestDetail(missingPrimitiveResult, "level is required");

    // 省略 @Nullable record component，成功
    mockMvc
        .perform(
            post("/test/record")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\": \"rec-1\", \"level\": 1}"))
        .andExpect(status().isOk());

    // 显式传入 null 的 @Nullable record component，成功
    mockMvc
        .perform(
            post("/test/record")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\": \"rec-1\", \"note\": null, \"level\": 1}"))
        .andExpect(status().isOk());
  }

  @Test
  void arraysValidateElementsAndMalformedJsonIsRejected() throws Exception {
    // 数组与集合相同地递归校验；JSON 解析失败不能绕过边界。
    mockMvc
        .perform(
            post("/test/array")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"code\":\"ok\"}]}"))
        .andExpect(status().isOk());
    MvcResult result =
        mockMvc
            .perform(
                post("/test/array")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"items\":[null]}"))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertBadRequestDetail(result, "items[0] is required");
    mockMvc
        .perform(post("/test/array").contentType(MediaType.APPLICATION_JSON).content("{invalid"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void realWriteOnlySecretPatchAcceptsSetAndOmittedValue() throws Exception {
    // 生产 DTO 的只写字段仍参与请求校验，不能依赖响应方向的 getter。
    for (String patch :
        List.of("{\"operation\":\"KEEP\"}", "{\"operation\":\"SET\",\"value\":\"token\"}")) {
      mockMvc
          .perform(
              post("/test/plugin")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"expectedVersion\":\"0\",\"valuesJson\":\"{}\",\"secretPatch\":{\"key\":"
                          + patch
                          + "}}"))
          .andExpect(status().isOk());
    }
  }

  private static void assertBadRequestDetail(MvcResult result, String expectedDetail) {
    Exception resolved = result.getResolvedException();
    assertInstanceOf(
        ResponseStatusException.class,
        resolved,
        "Expected ResponseStatusException for required property validation");
    ResponseStatusException exception = (ResponseStatusException) resolved;
    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    assertEquals(expectedDetail, exception.getReason());
  }

  @RestController
  static final class TestProbeController {

    @PostMapping("/test/basic")
    public void basic(@RequestBody BasicDTO body) {}

    @PostMapping("/test/primitive")
    public void primitive(@RequestBody PrimitiveDTO body) {}

    @PostMapping("/test/default-value")
    public void defaultValue(@RequestBody DefaultValueDTO body) {}

    @PostMapping("/test/nullable")
    public void nullable(@RequestBody NullableDTO body) {}

    @PostMapping("/test/nested")
    public void nested(@RequestBody OuterDTO body) {}

    @PostMapping("/test/list")
    public void list(@RequestBody ListHolderDTO body) {}

    @PostMapping("/test/scalar-list")
    public void scalarList(@RequestBody ScalarListDTO body) {}

    @PostMapping("/test/nullable-scalar-list")
    public void nullableScalarList(@RequestBody NullableScalarListDTO body) {}

    @PostMapping("/test/typed-map")
    public void typedMap(@RequestBody TypedMapDTO body) {}

    @PostMapping("/test/open-map")
    public void openMap(@RequestBody OpenMapDTO body) {}

    @PostMapping("/test/root-open-map")
    public void rootOpenMap(@RequestBody Map<String, Object> body) {}

    @PostMapping("/test/polymorphic")
    public void polymorphic(@RequestBody AnimalDTO body) {}

    @PostMapping("/test/inheritance")
    public void inheritance(@RequestBody ChildDTO body) {}

    @PostMapping("/test/record")
    public void record(@RequestBody SampleRecord body) {}

    @PostMapping("/test/plugin")
    public void plugin(@RequestBody PluginConfigurationUpdateRequestDTO body) {}

    @PostMapping("/test/canvas")
    public void canvas(@RequestBody ApplyCanvasCommandsRequestDTO body) {}

    @PostMapping("/test/array")
    public void array(@RequestBody ArrayDTO body) {}

    @PostMapping("/test/nested-map")
    public void nestedMap(@RequestBody NestedMapDTO body) {}
  }

  record ArrayDTO(ListItemDTO[] items) {}

  record NestedMapDTO(Map<String, @Nullable List<ListItemDTO>> items) {}

  static class BasicDTO {
    private String name;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }
  }

  static class PrimitiveDTO {
    private int count;
    private boolean active;

    public int getCount() {
      return count;
    }

    public void setCount(int count) {
      this.count = count;
    }

    public boolean isActive() {
      return active;
    }

    public void setActive(boolean active) {
      this.active = active;
    }
  }

  static class DefaultValueDTO {
    private String status = "ACTIVE";

    public String getStatus() {
      return status;
    }

    public void setStatus(String status) {
      this.status = status;
    }
  }

  static class NullableDTO {
    private String requiredTitle;
    private @Nullable String optionalDescription;

    public String getRequiredTitle() {
      return requiredTitle;
    }

    public void setRequiredTitle(String requiredTitle) {
      this.requiredTitle = requiredTitle;
    }

    public @Nullable String getOptionalDescription() {
      return optionalDescription;
    }

    public void setOptionalDescription(@Nullable String optionalDescription) {
      this.optionalDescription = optionalDescription;
    }
  }

  static class OuterDTO {
    private InnerAddressDTO address;
    private @Nullable InnerAddressDTO optionalBackup;

    public InnerAddressDTO getAddress() {
      return address;
    }

    public void setAddress(InnerAddressDTO address) {
      this.address = address;
    }

    public @Nullable InnerAddressDTO getOptionalBackup() {
      return optionalBackup;
    }

    public void setOptionalBackup(@Nullable InnerAddressDTO optionalBackup) {
      this.optionalBackup = optionalBackup;
    }
  }

  static class InnerAddressDTO {
    private String city;

    public String getCity() {
      return city;
    }

    public void setCity(String city) {
      this.city = city;
    }
  }

  static class ListHolderDTO {
    private List<ListItemDTO> items;

    public List<ListItemDTO> getItems() {
      return items;
    }

    public void setItems(List<ListItemDTO> items) {
      this.items = items;
    }
  }

  static class ListItemDTO {
    private String code;

    public String getCode() {
      return code;
    }

    public void setCode(String code) {
      this.code = code;
    }
  }

  static class ScalarListDTO {
    private List<String> tags;

    public List<String> getTags() {
      return tags;
    }

    public void setTags(List<String> tags) {
      this.tags = tags;
    }
  }

  static class NullableScalarListDTO {
    private List<@Nullable String> tags;

    public List<@Nullable String> getTags() {
      return tags;
    }

    public void setTags(List<@Nullable String> tags) {
      this.tags = tags;
    }
  }

  static class TypedMapDTO {
    private Map<String, ListItemDTO> itemMap;

    public Map<String, ListItemDTO> getItemMap() {
      return itemMap;
    }

    public void setItemMap(Map<String, ListItemDTO> itemMap) {
      this.itemMap = itemMap;
    }
  }

  static class OpenMapDTO {
    private Map<String, Object> extra;

    public Map<String, Object> getExtra() {
      return extra;
    }

    public void setExtra(Map<String, Object> extra) {
      this.extra = extra;
    }
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({@JsonSubTypes.Type(value = DogDTO.class, name = "dog")})
  interface AnimalDTO {}

  static class DogDTO implements AnimalDTO {
    private String dogName;

    public String getDogName() {
      return dogName;
    }

    public void setDogName(String dogName) {
      this.dogName = dogName;
    }
  }

  static class SuperDTO {
    private String superProperty;

    public String getSuperProperty() {
      return superProperty;
    }

    public void setSuperProperty(String superProperty) {
      this.superProperty = superProperty;
    }
  }

  static class ChildDTO extends SuperDTO {
    private String subProperty;

    public String getSubProperty() {
      return subProperty;
    }

    public void setSubProperty(String subProperty) {
      this.subProperty = subProperty;
    }
  }

  record SampleRecord(String id, @Nullable String note, int level) {}
}
