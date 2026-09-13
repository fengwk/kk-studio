package fun.fengwk.kkstudio.platform.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;

import java.util.Map;
import java.util.UUID;

/**
 * {@link ProjectToolExecutionSupport} 单元测试。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li>验证 JSON 解析与序列化；
 *   <li>验证 UUID、非负长整型、非空字符串校验；
 *   <li>验证各类异常消息脱敏。
 * </ul>
 */
class ProjectToolExecutionSupportTest {

  @Test
  void parseJson_validAndBlank() {
    // 验证正常解析与空串返回空对象节点
    JsonNode node = ProjectToolExecutionSupport.parseJson("{\"a\": 1}");
    assertEquals(1, node.get("a").asInt());

    JsonNode emptyNode = ProjectToolExecutionSupport.parseJson("");
    assertTrue(emptyNode.isObject());
    assertTrue(emptyNode.isEmpty());

    JsonNode nullNode = ProjectToolExecutionSupport.parseJson(null);
    assertTrue(nullNode.isObject());
    assertTrue(nullNode.isEmpty());
  }

  @Test
  void parseJson_invalidThrows() {
    // 验证无效 JSON 格式拦截
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> ProjectToolExecutionSupport.parseJson("invalid json"));
    assertEquals("Invalid JSON arguments", ex.getMessage());
  }

  @Test
  void toJson_success() {
    // 验证序列化成功
    String json = ProjectToolExecutionSupport.toJson(Map.of("k", "v"));
    assertTrue(json.contains("\"k\" : \"v\""));
  }

  @Test
  void parseUuid_validAndBlankAndInvalid() {
    // 验证 UUID 仅接受完整 canonical 文本，拒绝 Java UUID parser 可扩展的缩写形式
    UUID id = UUID.randomUUID();
    assertEquals(id, ProjectToolExecutionSupport.parseUuid(id.toString(), "field_id"));
    assertEquals(
        id, ProjectToolExecutionSupport.parseUuid(id.toString().toUpperCase(), "field_id"));

    IllegalArgumentException exBlank =
        assertThrows(
            IllegalArgumentException.class,
            () -> ProjectToolExecutionSupport.parseUuid("  ", "field_id"));
    assertEquals("field_id must not be blank", exBlank.getMessage());

    IllegalArgumentException exInvalid =
        assertThrows(
            IllegalArgumentException.class,
            () -> ProjectToolExecutionSupport.parseUuid("not-a-uuid", "field_id"));
    assertEquals("Invalid field_id format", exInvalid.getMessage());
    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectToolExecutionSupport.parseUuid("1-1-1-1-1", "field_id"));
  }

  @Test
  void requireNonNegativeLong_cases() {
    // 验证非负长整型校验
    JsonNode node =
        ProjectToolExecutionSupport.parseJson(
            "{\"valid\":5,\"negative\":-1,\"str\":\"123\",\"overflow\":9223372036854775808}");
    assertEquals(5L, ProjectToolExecutionSupport.requireNonNegativeLong(node, "valid"));

    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectToolExecutionSupport.requireNonNegativeLong(node, "negative"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectToolExecutionSupport.requireNonNegativeLong(node, "str"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectToolExecutionSupport.requireNonNegativeLong(node, "overflow"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectToolExecutionSupport.requireNonNegativeLong(node, "missing"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectToolExecutionSupport.requireNonNegativeLong(null, "valid"));
  }

  @Test
  void requireNonBlankString_cases() {
    // 验证非空字符串校验
    JsonNode node =
        ProjectToolExecutionSupport.parseJson(
            "{\"text\": \" hello \", \"blank\": \"   \", \"num\": 123}");
    assertEquals("hello", ProjectToolExecutionSupport.requireNonBlankString(node, "text"));

    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectToolExecutionSupport.requireNonBlankString(node, "blank"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectToolExecutionSupport.requireNonBlankString(node, "num"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectToolExecutionSupport.requireNonBlankString(node, "missing"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectToolExecutionSupport.requireNonBlankString(null, "text"));
  }

  @Test
  void sanitizeErrorMessage_variousExceptions() {
    // 验证各种异常的脱敏转换
    AiResourceNotFoundException rnf = new AiResourceNotFoundException("issue");
    assertEquals("issue not found", rnf.getMessage());
    assertEquals("issue not found", ProjectToolExecutionSupport.sanitizeErrorMessage(rnf));

    AiVersionConflictException vc = new AiVersionConflictException("issue", "1", "2");
    assertEquals("issue version conflict: expected=1 actual=2", vc.getMessage());
    assertEquals(
        "issue version conflict: expected=1 actual=2",
        ProjectToolExecutionSupport.sanitizeErrorMessage(vc));

    AiValidationException valEx = new AiValidationException("title", "Custom validation failed");
    assertEquals(
        "Custom validation failed", ProjectToolExecutionSupport.sanitizeErrorMessage(valEx));

    AiValidationException valExEmpty = new AiValidationException("title", "");
    assertEquals("Validation failed", ProjectToolExecutionSupport.sanitizeErrorMessage(valExEmpty));

    IllegalArgumentException iae = new IllegalArgumentException("Bad input");
    assertEquals("Bad input", ProjectToolExecutionSupport.sanitizeErrorMessage(iae));

    IllegalArgumentException iaeEmpty = new IllegalArgumentException("");
    assertEquals("Invalid request", ProjectToolExecutionSupport.sanitizeErrorMessage(iaeEmpty));

    assertEquals(
        "Project thread ownership is inconsistent",
        ProjectToolExecutionSupport.sanitizeErrorMessage(
            new IllegalStateException("Project thread ownership is inconsistent")));
    assertEquals(
        "Project role tool execution failed",
        ProjectToolExecutionSupport.sanitizeErrorMessage(
            new IllegalStateException(
                "Failed for 00000000-0000-0000-0000-000000000001 and secret")));

    RuntimeException unknown = new RuntimeException("DB Connection Timeout at 192.168.1.1");
    assertEquals(
        "Project role tool execution failed",
        ProjectToolExecutionSupport.sanitizeErrorMessage(unknown));
  }
}
