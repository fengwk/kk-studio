package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 适配 LangChain4j Anthropic Mapper、Schema 与 Cache 测试到 kk-studio 原生请求编码器的端到端映射套件。
 *
 * <p>直接针对 {@link AnthropicRequestEncoder} 进行断言，覆盖消息矩阵、工具定义映射、 JSON Schema 透传、缓存断点注入、诊断隔离、采样惩罚校验、会话中
 * SYSTEM 拦截与非法结构拒绝。
 */
class AnthropicRequestMapperTest {

  private static final String DICE_IMAGE_URL =
      "https://upload.wikimedia.org/wikipedia/commons/4/47/PNG_transparency_demonstration_1.png";

  private static final String BASE64_IMAGE_DATA = "iVBORw0KGgo=";

  private static final String BASE64_PDF_DATA = "JVBERi0xLjQK";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final AnthropicRequestEncoder encoder = new AnthropicRequestEncoder();

  private final ProviderDescriptor descriptor =
      new ProviderDescriptor(
          "test-anthropic",
          ProviderType.ANTHROPIC,
          "https://api.anthropic.com/v1",
          new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)),
          new UUID(1L, 2L));

  // =========================================================================================
  // 1. 14-case 消息映射矩阵
  // =========================================================================================

  /**
   * 测试意图：覆盖 14 种运行时可表达的消息组合，验证文本、系统提示词、多轮会话、 工具调用、思考块降级、Base64/URL 图片、Base64 PDF 的正确线缆映射，并对不支持的 URL
   * PDF 和非法会话中 SYSTEM 显式验证确定性失败。
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource
  void test_toAnthropicMessages(MessageMappingCase testCase) throws IOException {
    ProviderRequest request =
        request(defaultVariant(), testCase.messages(), List.of(), ProviderCacheControl.none());

    if (testCase.expectedErrorKind() != null) {
      ProviderException exception =
          assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
      assertEquals(testCase.expectedErrorKind(), exception.kind());
      if (testCase.expectedErrorMessageSubstring() != null) {
        assertTrue(
            exception.getMessage().contains(testCase.expectedErrorMessageSubstring()),
            "Expected error message to contain: "
                + testCase.expectedErrorMessageSubstring()
                + " but was: "
                + exception.getMessage());
      }
      return;
    }

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode expectedMessagesNode = MAPPER.readTree(testCase.expectedMessagesJson());
    assertEquals(expectedMessagesNode, root.path("messages"));

    if (testCase.expectedSystemJson() != null) {
      JsonNode expectedSystemNode = MAPPER.readTree(testCase.expectedSystemJson());
      assertEquals(expectedSystemNode, root.path("system"));
    } else {
      assertFalse(root.has("system"));
    }
  }

  static Stream<Arguments> test_toAnthropicMessages() {
    return Stream.of(
        // Case 1: 单条用户文本消息
        Arguments.of(
            MessageMappingCase.success(
                "case1_single_user_text",
                List.of(userMsg(new ProviderTextBlock("Hello"))),
                """
                [
                  {
                    "role": "user",
                    "content": [{"type": "text", "text": "Hello"}]
                  }
                ]
                """,
                null)),

        // Case 2: 前置系统消息提取至顶层 system 字段，不进入 messages 数组
        Arguments.of(
            MessageMappingCase.success(
                "case2_leading_system_extracted_to_top_level",
                List.of(
                    sysMsg(new ProviderTextBlock("Ignored")),
                    userMsg(new ProviderTextBlock("Hello"))),
                """
                [
                  {
                    "role": "user",
                    "content": [{"type": "text", "text": "Hello"}]
                  }
                ]
                """,
                """
                [
                  {"type": "text", "text": "Ignored"}
                ]
                """)),

        // Case 3: 会话中间的系统消息（非前置）必须在发起网络请求前被确定性拦截
        Arguments.of(
            MessageMappingCase.failure(
                "case3_mid_conversation_system_rejected",
                List.of(
                    userMsg(new ProviderTextBlock("Hello")),
                    sysMsg(new ProviderTextBlock("Ignored"))),
                ProviderErrorKind.INVALID_REQUEST,
                "Anthropic does not allow mid-conversation SYSTEM messages")),

        // Case 4: 多轮 User -> Assistant -> User 文本会话映射
        Arguments.of(
            MessageMappingCase.success(
                "case4_multi_turn_conversation",
                List.of(
                    userMsg(new ProviderTextBlock("Hello")),
                    asstMsg(new ProviderTextBlock("Hi")),
                    userMsg(new ProviderTextBlock("How are you?"))),
                """
                [
                  {
                    "role": "user",
                    "content": [{"type": "text", "text": "Hello"}]
                  },
                  {
                    "role": "assistant",
                    "content": [{"type": "text", "text": "Hi"}]
                  },
                  {
                    "role": "user",
                    "content": [{"type": "text", "text": "How are you?"}]
                  }
                ]
                """,
                null)),

        // Case 5: 单次工具调用（assistant tool_use）与工具响应（user role tool_result）
        Arguments.of(
            MessageMappingCase.success(
                "case5_tool_use_and_tool_result",
                List.of(
                    userMsg(new ProviderTextBlock("How much is 2+2?")),
                    asstToolMsg(
                        new ProviderToolCall(
                            "12345", "calculator", "{\"first\": 2, \"second\": 2}")),
                    toolResultMsg("12345", "calculator", false, new ProviderTextBlock("4"))),
                """
                [
                  {
                    "role": "user",
                    "content": [{"type": "text", "text": "How much is 2+2?"}]
                  },
                  {
                    "role": "assistant",
                    "content": [
                      {
                        "type": "tool_use",
                        "id": "12345",
                        "name": "calculator",
                        "input": {"first": 2, "second": 2}
                      }
                    ]
                  },
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "tool_result",
                        "tool_use_id": "12345",
                        "content": [{"type": "text", "text": "4"}]
                      }
                    ]
                  }
                ]
                """,
                null)),

        // Case 6: 带有 thinking 块的 assistant 工具调用降级为普通 text 块后紧随 tool_use
        Arguments.of(
            MessageMappingCase.success(
                "case6_assistant_thinking_fallback_with_tool_use",
                List.of(
                    userMsg(new ProviderTextBlock("How much is 2+2?")),
                    asstMsg(
                        new ProviderThinkingBlock(
                            "<thinking>I need to use the calculator tool</thinking>"),
                        new ProviderToolCallBlock(
                            new ProviderToolCall(
                                "12345", "calculator", "{\"first\": 2, \"second\": 2}"))),
                    toolResultMsg("12345", "calculator", false, new ProviderTextBlock("4"))),
                """
                [
                  {
                    "role": "user",
                    "content": [{"type": "text", "text": "How much is 2+2?"}]
                  },
                  {
                    "role": "assistant",
                    "content": [
                      {
                        "type": "text",
                        "text": "<thinking>I need to use the calculator tool</thinking>"
                      },
                      {
                        "type": "tool_use",
                        "id": "12345",
                        "name": "calculator",
                        "input": {"first": 2, "second": 2}
                      }
                    ]
                  },
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "tool_result",
                        "tool_use_id": "12345",
                        "content": [{"type": "text", "text": "4"}]
                      }
                    ]
                  }
                ]
                """,
                null)),

        // Case 7: 同一轮次多个并行工具调用与其对应的多个工具返回
        Arguments.of(
            MessageMappingCase.success(
                "case7_parallel_tool_use_and_results",
                List.of(
                    userMsg(new ProviderTextBlock("How much is 2+2 and 3+3?")),
                    asstToolMsg(
                        new ProviderToolCall(
                            "12345", "calculator", "{\"first\": 2, \"second\": 2}"),
                        new ProviderToolCall(
                            "67890", "calculator", "{\"first\": 3, \"second\": 3}")),
                    toolResultMsg("12345", "calculator", false, new ProviderTextBlock("4")),
                    toolResultMsg("67890", "calculator", false, new ProviderTextBlock("6"))),
                """
                [
                  {
                    "role": "user",
                    "content": [{"type": "text", "text": "How much is 2+2 and 3+3?"}]
                  },
                  {
                    "role": "assistant",
                    "content": [
                      {
                        "type": "tool_use",
                        "id": "12345",
                        "name": "calculator",
                        "input": {"first": 2, "second": 2}
                      },
                      {
                        "type": "tool_use",
                        "id": "67890",
                        "name": "calculator",
                        "input": {"first": 3, "second": 3}
                      }
                    ]
                  },
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "tool_result",
                        "tool_use_id": "12345",
                        "content": [{"type": "text", "text": "4"}]
                      }
                    ]
                  },
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "tool_result",
                        "tool_use_id": "67890",
                        "content": [{"type": "text", "text": "6"}]
                      }
                    ]
                  }
                ]
                """,
                null)),

        // Case 8: 跨轮次顺序工具调用与返回
        Arguments.of(
            MessageMappingCase.success(
                "case8_sequential_tool_use_across_turns",
                List.of(
                    userMsg(new ProviderTextBlock("How much is 2+2 and 3+3?")),
                    asstToolMsg(
                        new ProviderToolCall(
                            "12345", "calculator", "{\"first\": 2, \"second\": 2}")),
                    toolResultMsg("12345", "calculator", false, new ProviderTextBlock("4")),
                    asstToolMsg(
                        new ProviderToolCall(
                            "67890", "calculator", "{\"first\": 3, \"second\": 3}")),
                    toolResultMsg("67890", "calculator", false, new ProviderTextBlock("6"))),
                """
                [
                  {
                    "role": "user",
                    "content": [{"type": "text", "text": "How much is 2+2 and 3+3?"}]
                  },
                  {
                    "role": "assistant",
                    "content": [
                      {
                        "type": "tool_use",
                        "id": "12345",
                        "name": "calculator",
                        "input": {"first": 2, "second": 2}
                      }
                    ]
                  },
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "tool_result",
                        "tool_use_id": "12345",
                        "content": [{"type": "text", "text": "4"}]
                      }
                    ]
                  },
                  {
                    "role": "assistant",
                    "content": [
                      {
                        "type": "tool_use",
                        "id": "67890",
                        "name": "calculator",
                        "input": {"first": 3, "second": 3}
                      }
                    ]
                  },
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "tool_result",
                        "tool_use_id": "67890",
                        "content": [{"type": "text", "text": "6"}]
                      }
                    ]
                  }
                ]
                """,
                null)),

        // Case 9: URL 图片块
        Arguments.of(
            MessageMappingCase.success(
                "case9_url_image_block",
                List.of(userMsg(new ProviderImageBlock("image/png", DICE_IMAGE_URL))),
                """
                [
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "image",
                        "source": {
                          "type": "url",
                          "url": "https://upload.wikimedia.org/wikipedia/commons/4/47/PNG_transparency_demonstration_1.png"
                        }
                      }
                    ]
                  }
                ]
                """,
                null)),

        // Case 10: Base64 图片块（从 data URI 提取）
        Arguments.of(
            MessageMappingCase.success(
                "case10_base64_image_block",
                List.of(
                    userMsg(
                        new ProviderImageBlock(
                            "image/jpeg", "data:image/jpeg;base64," + BASE64_IMAGE_DATA))),
                """
                [
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "image",
                        "source": {
                          "type": "base64",
                          "media_type": "image/jpeg",
                          "data": "iVBORw0KGgo="
                        }
                      }
                    ]
                  }
                ]
                """,
                null)),

        // Case 11: 文本与 URL 图片混合内容
        Arguments.of(
            MessageMappingCase.success(
                "case11_text_and_url_image",
                List.of(
                    userMsg(
                        new ProviderTextBlock("Describe this image"),
                        new ProviderImageBlock("image/png", DICE_IMAGE_URL))),
                """
                [
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "text",
                        "text": "Describe this image"
                      },
                      {
                        "type": "image",
                        "source": {
                          "type": "url",
                          "url": "https://upload.wikimedia.org/wikipedia/commons/4/47/PNG_transparency_demonstration_1.png"
                        }
                      }
                    ]
                  }
                ]
                """,
                null)),

        // Case 12: URL PDF 文档（Anthropic 仅支持 Base64 文档，URL PDF 必须显式拒绝而非假装支持）
        Arguments.of(
            MessageMappingCase.failure(
                "case12_url_pdf_unsupported_must_reject",
                List.of(
                    userMsg(
                        new ProviderDocumentBlock(
                            "application/pdf", "https://example.com/document.pdf"))),
                ProviderErrorKind.INVALID_REQUEST,
                "document source must be a valid base64 data URI matching mediaType")),

        // Case 13: Base64 PDF 文档（从 data URI 提取）
        Arguments.of(
            MessageMappingCase.success(
                "case13_base64_pdf_block",
                List.of(
                    userMsg(
                        new ProviderDocumentBlock(
                            "application/pdf", "data:application/pdf;base64," + BASE64_PDF_DATA))),
                """
                [
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "document",
                        "source": {
                          "type": "base64",
                          "media_type": "application/pdf",
                          "data": "JVBERi0xLjQK"
                        }
                      }
                    ]
                  }
                ]
                """,
                null)),

        // Case 14: 文本与 URL PDF 混合内容（同样由于不支持 URL PDF 而确定性失败）
        Arguments.of(
            MessageMappingCase.failure(
                "case14_text_and_url_pdf_unsupported_must_reject",
                List.of(
                    userMsg(
                        new ProviderTextBlock("Analyze this document"),
                        new ProviderDocumentBlock(
                            "application/pdf", "https://example.com/document.pdf"))),
                ProviderErrorKind.INVALID_REQUEST,
                "document source must be a valid base64 data URI matching mediaType")));
  }

  // =========================================================================================
  // 2. 参数化工具定义映射
  // =========================================================================================

  /**
   * 测试意图：验证工具定义的 name、description 与 input_schema 在标准有参和无参场景下的线缆映射， 确保生成的 tools 数组元素满足 Anthropic
   * Messages 协议。
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource
  void test_toAnthropicTool(
      String testName, ProviderToolDefinition toolDef, String expectedToolJson) throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(toolDef),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode actualTool = root.path("tools").get(0);
    JsonNode expectedTool = MAPPER.readTree(expectedToolJson);
    assertEquals(expectedTool, actualTool);
  }

  static Stream<Arguments> test_toAnthropicTool() {
    return Stream.of(
        Arguments.of(
            "tool_with_parameter_schema",
            new ProviderToolDefinition(
                "name",
                "description",
                """
                {
                  "type": "object",
                  "properties": {
                    "parameter": {
                      "type": "string"
                    }
                  },
                  "required": ["parameter"]
                }
                """),
            """
            {
              "name": "name",
              "description": "description",
              "input_schema": {
                "type": "object",
                "properties": {
                  "parameter": {
                    "type": "string"
                  }
                },
                "required": ["parameter"]
              }
            }
            """),
        Arguments.of(
            "tool_without_parameters",
            new ProviderToolDefinition(
                "tool",
                "description for tool without parameters",
                """
                {
                  "type": "object",
                  "properties": {},
                  "required": []
                }
                """),
            """
            {
              "name": "tool",
              "description": "description for tool without parameters",
              "input_schema": {
                "type": "object",
                "properties": {},
                "required": []
              }
            }
            """));
  }

  // =========================================================================================
  // 3. JSON Schema 透传验证
  // =========================================================================================

  /** 测试意图：验证带有多个必填字段的标准对象 JSON Schema 正确无损透传至 input_schema。 */
  @Test
  void test_toAnthropicSchema_with_objects() throws IOException {
    String schemaJson = loadFixture("object_schema.json");
    ProviderToolDefinition tool =
        new ProviderToolDefinition("create_user", "Registers user", schemaJson);

    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("register"))),
            List.of(tool),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode actualInputSchema = root.path("tools").get(0).path("input_schema");
    assertEquals(MAPPER.readTree(schemaJson), actualInputSchema);
  }

  /** 测试意图：验证带有 $defs 与 $ref 的引用型 JSON Schema 正确透传，且 $defs 键名保持原样，不被修改为 defs。 */
  @Test
  void test_toAnthropicSchema_with_definitions() throws IOException {
    String schemaJson = loadFixture("definitions_schema.json");
    ProviderToolDefinition tool =
        new ProviderToolDefinition("save_person", "Saves person", schemaJson);

    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("save"))),
            List.of(tool),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode actualInputSchema = root.path("tools").get(0).path("input_schema");
    assertEquals(MAPPER.readTree(schemaJson), actualInputSchema);
    assertTrue(actualInputSchema.has("$defs"));
    assertFalse(actualInputSchema.has("defs"));
    assertEquals(
        "#/$defs/Person",
        actualInputSchema.path("properties").path("person").path("$ref").asText());
  }

  /** 测试意图：验证工具定义在携带 $defs 结构时，生成的 wire 工具正确保留 $defs。 */
  @Test
  void test_toAnthropicTool_with_definitions() throws IOException {
    String schemaJson = loadFixture("definitions_schema.json");
    ProviderToolDefinition tool =
        new ProviderToolDefinition("tool_with_defs", "Tool with defs", schemaJson);

    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("call"))),
            List.of(tool),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode inputSchema = root.path("tools").get(0).path("input_schema");
    assertTrue(inputSchema.has("$defs"));
    assertTrue(inputSchema.path("$defs").has("Person"));
  }

  /** 测试意图：验证工具定义在没有 $defs 时，线缆上的 input_schema 绝不额外注入 $defs 字段。 */
  @Test
  void test_toAnthropicTool_without_definitions_omits_defs() throws IOException {
    String schemaJson =
        """
        {
          "type": "object",
          "properties": {
            "parameter": {"type": "string"}
          },
          "required": ["parameter"]
        }
        """;
    ProviderToolDefinition tool =
        new ProviderToolDefinition("simple_tool", "Simple tool", schemaJson);

    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("call"))),
            List.of(tool),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode inputSchema = root.path("tools").get(0).path("input_schema");
    assertFalse(inputSchema.has("$defs"));
  }

  /** 测试意图：验证包含可选字段和 enum 的 Schema 正确透传，且保留各属性类型与选项值。 */
  @Test
  void test_toAnthropicSchema_with_optional_fields() throws IOException {
    String schemaJson = loadFixture("optional_fields_schema.json");
    ProviderToolDefinition tool = new ProviderToolDefinition("save_book", "Saves book", schemaJson);

    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("book"))),
            List.of(tool),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode actualInputSchema = root.path("tools").get(0).path("input_schema");
    assertEquals(MAPPER.readTree(schemaJson), actualInputSchema);
  }

  // =========================================================================================
  // 4. 缓存标记控制适配测试（ProviderCacheControl 驱动）
  // =========================================================================================

  /** 测试意图：当配置 CONVERSATION 断点时，单条文本用户消息的内容块应被打上 ephemeral 缓存标记。 */
  @Test
  void should_map_user_message_with_cache_control_metadata() throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("Hello cached world"))),
            List.of(),
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.SHORT,
                "affinity-1",
                Set.of(PromptCacheBreakpoint.CONVERSATION)));

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode contentBlock = root.path("messages").get(0).path("content").get(0);
    assertEquals("Hello cached world", contentBlock.path("text").asText());
    assertEquals("ephemeral", contentBlock.path("cache_control").path("type").asText());
    assertFalse(contentBlock.path("cache_control").has("ttl"));
  }

  /** 测试意图：当用户消息包含多个内容块时，只有最后一个内容块打上缓存标记，前面的内容块不带标记。 */
  @Test
  void should_only_apply_cache_control_to_last_item_when_multiple_items_present()
      throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                userMsg(new ProviderTextBlock("First item"), new ProviderTextBlock("Second item"))),
            List.of(),
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.SHORT,
                "affinity-2",
                Set.of(PromptCacheBreakpoint.CONVERSATION)));

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode contents = root.path("messages").get(0).path("content");
    assertEquals(2, contents.size());
    assertEquals("First item", contents.get(0).path("text").asText());
    assertFalse(contents.get(0).has("cache_control"));

    assertEquals("Second item", contents.get(1).path("text").asText());
    assertEquals("ephemeral", contents.get(1).path("cache_control").path("type").asText());
  }

  /** 测试意图：当会话结尾为 Assistant 文本消息时，CONVERSATION 断点标记应正确注入至该 Assistant 内容块。 */
  @Test
  void should_map_ai_message_text_with_cache_control_metadata() throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("Hello")), asstMsg(new ProviderTextBlock("Hi"))),
            List.of(),
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.SHORT,
                "affinity-3",
                Set.of(PromptCacheBreakpoint.CONVERSATION)));

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode userBlock = root.path("messages").get(0).path("content").get(0);
    assertFalse(userBlock.has("cache_control"));

    JsonNode asstBlock = root.path("messages").get(1).path("content").get(0);
    assertEquals("Hi", asstBlock.path("text").asText());
    assertEquals("ephemeral", asstBlock.path("cache_control").path("type").asText());
  }

  /** 测试意图：当 Assistant 消息同时包含文本与工具调用时，缓存标记应打在最后一个内容块（tool_use）上，文本块不带标记。 */
  @Test
  void should_apply_cache_control_to_last_content_block_of_ai_message_with_tool_execution_requests()
      throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                userMsg(new ProviderTextBlock("Calculate")),
                asstMsg(
                    new ProviderTextBlock("Let me check that"),
                    new ProviderToolCallBlock(
                        new ProviderToolCall(
                            "12345", "calculator", "{\"first\": 2, \"second\": 2}")))),
            List.of(),
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.SHORT,
                "affinity-4",
                Set.of(PromptCacheBreakpoint.CONVERSATION)));

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode asstContents = root.path("messages").get(1).path("content");
    assertEquals(2, asstContents.size());
    assertFalse(asstContents.get(0).has("cache_control"));

    JsonNode toolUseNode = asstContents.get(1);
    assertEquals("tool_use", toolUseNode.path("type").asText());
    assertEquals("ephemeral", toolUseNode.path("cache_control").path("type").asText());
  }

  /** 测试意图：当缓存保留策略为 NONE 时，Assistant 消息绝不带有 cache_control 标记。 */
  @Test
  void should_not_apply_cache_control_to_ai_message_without_cache_control_attribute()
      throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("Hello")), asstMsg(new ProviderTextBlock("Hi"))),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode asstBlock = root.path("messages").get(1).path("content").get(0);
    assertFalse(asstBlock.has("cache_control"));
  }

  /** 测试意图：当会话结尾为单文本工具返回时，缓存标记应打在外层 tool_result 块上，内层 text 块不带标记。 */
  @Test
  void should_map_tool_execution_result_message_with_single_text_and_cache_control_metadata()
      throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                userMsg(new ProviderTextBlock("calculate")),
                asstToolMsg(
                    new ProviderToolCall("12345", "calculator", "{\"first\": 2, \"second\": 2}")),
                toolResultMsg("12345", "calculator", false, new ProviderTextBlock("4"))),
            List.of(),
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.SHORT,
                "affinity-5",
                Set.of(PromptCacheBreakpoint.CONVERSATION)));

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode toolResultNode = root.path("messages").get(2).path("content").get(0);
    assertEquals("tool_result", toolResultNode.path("type").asText());
    assertEquals("ephemeral", toolResultNode.path("cache_control").path("type").asText());

    JsonNode nestedTextNode = toolResultNode.path("content").get(0);
    assertFalse(nestedTextNode.has("cache_control"));
  }

  /** 测试意图：当工具返回包含多个内容块（如文本和图片）时，缓存标记打在外层 tool_result 块上，内部多个内容块均不带标记。 */
  @Test
  void
      should_map_tool_execution_result_message_with_multiple_content_blocks_and_cache_control_metadata()
          throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                userMsg(new ProviderTextBlock("chart")),
                asstToolMsg(new ProviderToolCall("12345", "calculator", "{}")),
                toolMsg(
                    new ProviderToolResultBlock(
                        "12345",
                        "calculator",
                        List.of(
                            new ProviderTextBlock("here is the chart"),
                            new ProviderImageBlock("image/png", DICE_IMAGE_URL)),
                        false,
                        "{}"))),
            List.of(),
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.SHORT,
                "affinity-6",
                Set.of(PromptCacheBreakpoint.CONVERSATION)));

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode toolResultNode = root.path("messages").get(2).path("content").get(0);
    assertEquals("tool_result", toolResultNode.path("type").asText());
    assertEquals("ephemeral", toolResultNode.path("cache_control").path("type").asText());

    JsonNode nestedBlocks = toolResultNode.path("content");
    assertEquals(2, nestedBlocks.size());
    assertFalse(nestedBlocks.get(0).has("cache_control"));
    assertFalse(nestedBlocks.get(1).has("cache_control"));
  }

  /** 测试意图：当策略为 NONE 时，tool_result 块绝不带 cache_control 标记。 */
  @Test
  void
      should_not_apply_cache_control_to_tool_execution_result_message_without_cache_control_attribute()
          throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                userMsg(new ProviderTextBlock("calculate")),
                asstToolMsg(new ProviderToolCall("12345", "calculator", "{}")),
                toolResultMsg("12345", "calculator", false, new ProviderTextBlock("4"))),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode toolResultNode = root.path("messages").get(2).path("content").get(0);
    assertFalse(toolResultNode.has("cache_control"));
  }

  /** 测试意图：用户消息以 URL 图片结尾时，前面的文本块无标记，末尾的图片块被打上缓存标记。 */
  @Test
  void should_apply_cache_control_to_last_content_block_of_user_message_ending_with_image()
      throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                userMsg(
                    new ProviderTextBlock("What is on this image?"),
                    new ProviderImageBlock("image/png", DICE_IMAGE_URL))),
            List.of(),
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.SHORT,
                "affinity-7",
                Set.of(PromptCacheBreakpoint.CONVERSATION)));

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode contents = root.path("messages").get(0).path("content");
    assertFalse(contents.get(0).has("cache_control"));

    JsonNode imageNode = contents.get(1);
    assertEquals("image", imageNode.path("type").asText());
    assertEquals("ephemeral", imageNode.path("cache_control").path("type").asText());
  }

  /** 测试意图：用户消息以 Base64 PDF 结尾时，前面的文本块无标记，末尾的 document 块被打上缓存标记。 */
  @Test
  void should_apply_cache_control_to_last_content_block_of_base64_pdf_message() throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                userMsg(
                    new ProviderTextBlock("What is in this document?"),
                    new ProviderDocumentBlock(
                        "application/pdf", "data:application/pdf;base64," + BASE64_PDF_DATA))),
            List.of(),
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.SHORT,
                "affinity-8",
                Set.of(PromptCacheBreakpoint.CONVERSATION)));

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode contents = root.path("messages").get(0).path("content");
    assertFalse(contents.get(0).has("cache_control"));

    JsonNode docNode = contents.get(1);
    assertEquals("document", docNode.path("type").asText());
    assertEquals("ephemeral", docNode.path("cache_control").path("type").asText());
  }

  /** 测试意图：当图片不是最后一个内容块（后面有文本）时，图片不打标记，最后的文本打上标记。 */
  @Test
  void should_not_apply_cache_control_to_image_content_when_not_last_item() throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                userMsg(
                    new ProviderImageBlock("image/png", DICE_IMAGE_URL),
                    new ProviderTextBlock("What is on this image?"))),
            List.of(),
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.SHORT,
                "affinity-9",
                Set.of(PromptCacheBreakpoint.CONVERSATION)));

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode contents = root.path("messages").get(0).path("content");
    assertFalse(contents.get(0).has("cache_control"));

    JsonNode textNode = contents.get(1);
    assertEquals("What is on this image?", textNode.path("text").asText());
    assertEquals("ephemeral", textNode.path("cache_control").path("type").asText());
  }

  /** 测试意图：验证单 Base64 图片内容块在启用 CONVERSATION 缓存时能正确标记为 ephemeral。 */
  @Test
  void should_apply_cache_control_to_base64_image_content() throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                userMsg(
                    new ProviderImageBlock(
                        "image/jpeg", "data:image/jpeg;base64," + BASE64_IMAGE_DATA))),
            List.of(),
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.SHORT,
                "affinity-10",
                Set.of(PromptCacheBreakpoint.CONVERSATION)));

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode imageNode = root.path("messages").get(0).path("content").get(0);
    assertEquals("base64", imageNode.path("source").path("type").asText());
    assertEquals("ephemeral", imageNode.path("cache_control").path("type").asText());
  }

  /** 测试意图：验证单 Base64 PDF 内容块在启用 CONVERSATION 缓存时能正确标记为 ephemeral。 */
  @Test
  void should_apply_cache_control_to_base64_pdf_content() throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                userMsg(
                    new ProviderDocumentBlock(
                        "application/pdf", "data:application/pdf;base64," + BASE64_PDF_DATA))),
            List.of(),
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.SHORT,
                "affinity-11",
                Set.of(PromptCacheBreakpoint.CONVERSATION)));

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode docNode = root.path("messages").get(0).path("content").get(0);
    assertEquals("base64", docNode.path("source").path("type").asText());
    assertEquals("ephemeral", docNode.path("cache_control").path("type").asText());
  }

  /** 测试意图：当策略为 NONE 时，图片内容块绝不带 cache_control 标记。 */
  @Test
  void should_not_apply_cache_control_to_image_content_without_cache_control_attribute()
      throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderImageBlock("image/png", DICE_IMAGE_URL))),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode imageNode = root.path("messages").get(0).path("content").get(0);
    assertFalse(imageNode.has("cache_control"));
  }

  /** 测试意图：当策略为 NONE 时，PDF 内容块绝不带 cache_control 标记。 */
  @Test
  void should_not_apply_cache_control_to_base64_pdf_without_breakpoint() throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                userMsg(
                    new ProviderDocumentBlock(
                        "application/pdf", "data:application/pdf;base64," + BASE64_PDF_DATA))),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode docNode = root.path("messages").get(0).path("content").get(0);
    assertFalse(docNode.has("cache_control"));
  }

  /** 测试意图：精确断言带缓存标记的图片内容块在线缆上的完整 JSON 结构（包含 source 与 cache_control 节点）。 */
  @Test
  void should_serialize_image_content_with_cache_control_next_to_source() throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderImageBlock("image/png", DICE_IMAGE_URL))),
            List.of(),
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.SHORT,
                "affinity-12",
                Set.of(PromptCacheBreakpoint.CONVERSATION)));

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode imageNode = root.path("messages").get(0).path("content").get(0);
    JsonNode expected =
        MAPPER.readTree(
            """
            {
              "type": "image",
              "cache_control": {"type": "ephemeral"},
              "source": {"type": "url", "url": "%s"}
            }
            """
                .formatted(DICE_IMAGE_URL));
    assertEquals(expected, imageNode);
  }

  /** 测试意图：精确断言带缓存标记的 Base64 PDF 内容块在线缆上的完整 JSON 结构。 */
  @Test
  void should_serialize_pdf_content_with_cache_control_next_to_source() throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                userMsg(
                    new ProviderDocumentBlock(
                        "application/pdf", "data:application/pdf;base64," + BASE64_PDF_DATA))),
            List.of(),
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.SHORT,
                "affinity-13",
                Set.of(PromptCacheBreakpoint.CONVERSATION)));

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode docNode = root.path("messages").get(0).path("content").get(0);
    JsonNode expected =
        MAPPER.readTree(
            """
            {
              "type": "document",
              "cache_control": {"type": "ephemeral"},
              "source": {
                "type": "base64",
                "media_type": "application/pdf",
                "data": "%s"
              }
            }
            """
                .formatted(BASE64_PDF_DATA));
    assertEquals(expected, docNode);
  }

  /** 测试意图：验证 LONG 保留策略注入 ttl="1h"，而 SHORT 保留策略省略 ttl 字段。 */
  @Test
  void should_include_ttl_1h_for_long_retention_and_omit_ttl_for_short_retention()
      throws IOException {
    // 1. LONG 保留策略
    ProviderRequest reqLong =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("Cached text"))),
            List.of(),
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.LONG,
                "affinity-long",
                Set.of(PromptCacheBreakpoint.CONVERSATION)));

    AnthropicEncodedRequest encLong = encoder.encode(reqLong, descriptor);
    JsonNode rootLong = MAPPER.readTree(encLong.bodyUtf8Bytes());
    JsonNode markerLong =
        rootLong.path("messages").get(0).path("content").get(0).path("cache_control");
    assertEquals("ephemeral", markerLong.path("type").asText());
    assertEquals("1h", markerLong.path("ttl").asText());

    // 2. SHORT 保留策略
    ProviderRequest reqShort =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("Cached text"))),
            List.of(),
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.SHORT,
                "affinity-short",
                Set.of(PromptCacheBreakpoint.CONVERSATION)));

    AnthropicEncodedRequest encShort = encoder.encode(reqShort, descriptor);
    JsonNode rootShort = MAPPER.readTree(encShort.bodyUtf8Bytes());
    JsonNode markerShort =
        rootShort.path("messages").get(0).path("content").get(0).path("cache_control");
    assertEquals("ephemeral", markerShort.path("type").asText());
    assertFalse(markerShort.has("ttl"));
  }

  /** 测试意图：当指定 SYSTEM 断点时，缓存标记必须打在最后一个系统块上，前面的系统块不带标记。 */
  @Test
  void should_mark_last_system_block_when_system_breakpoint_is_enabled() throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                sysMsg(new ProviderTextBlock("Leading instruction 1")),
                sysMsg(new ProviderTextBlock("Leading instruction 2")),
                userMsg(new ProviderTextBlock("Hi"))),
            List.of(),
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.SHORT, "affinity-sys", Set.of(PromptCacheBreakpoint.SYSTEM)));

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode systemArray = root.path("system");
    assertEquals(2, systemArray.size());
    assertFalse(systemArray.get(0).has("cache_control"));

    JsonNode lastSystem = systemArray.get(1);
    assertEquals("ephemeral", lastSystem.path("cache_control").path("type").asText());

    // messages 数组无标记
    assertFalse(root.path("messages").get(0).path("content").get(0).has("cache_control"));
  }

  /** 测试意图：当断点集合中未包含 SYSTEM 时，即使存在前置系统消息也不注入缓存标记。 */
  @Test
  void should_leave_system_blocks_unmarked_without_system_breakpoint() throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                sysMsg(new ProviderTextBlock("Leading instruction")),
                userMsg(new ProviderTextBlock("Hi"))),
            List.of(),
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.SHORT,
                "affinity-conv",
                Set.of(PromptCacheBreakpoint.CONVERSATION)));

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode systemNode = root.path("system").get(0);
    assertFalse(systemNode.has("cache_control"));

    // CONVERSATION 断点仍生效
    JsonNode userNode = root.path("messages").get(0).path("content").get(0);
    assertEquals("ephemeral", userNode.path("cache_control").path("type").asText());
  }

  /** 测试意图：当指定 TOOLS 断点时，缓存标记必须打在最后一个工具定义上，前面的工具不带标记。 */
  @Test
  void should_mark_last_tool_when_tools_breakpoint_is_enabled() throws IOException {
    ProviderToolDefinition tool1 =
        new ProviderToolDefinition(
            "calc1", "first calculator", "{\"type\":\"object\",\"properties\":{}}");
    ProviderToolDefinition tool2 =
        new ProviderToolDefinition(
            "calc2", "second calculator", "{\"type\":\"object\",\"properties\":{}}");

    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("What is the weather?"))),
            List.of(tool1, tool2),
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.SHORT, "affinity-tools", Set.of(PromptCacheBreakpoint.TOOLS)));

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode toolsArray = root.path("tools");
    assertEquals(2, toolsArray.size());
    assertFalse(toolsArray.get(0).has("cache_control"));

    JsonNode lastTool = toolsArray.get(1);
    assertEquals("ephemeral", lastTool.path("cache_control").path("type").asText());
  }

  /** 测试意图：当断点集合中未包含 TOOLS 时，工具定义数组中不注入缓存标记。 */
  @Test
  void should_leave_tools_unmarked_without_tools_breakpoint() throws IOException {
    ProviderToolDefinition tool =
        new ProviderToolDefinition("calc", "calculator", "{\"type\":\"object\",\"properties\":{}}");

    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("What is the weather?"))),
            List.of(tool),
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.SHORT,
                "affinity-conv-only",
                Set.of(PromptCacheBreakpoint.CONVERSATION)));

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    assertFalse(root.path("tools").get(0).has("cache_control"));
  }

  /** 测试意图：当请求指定 ProviderCacheControl.none() 时，整条请求中的 system、tools 与 messages 均无缓存标记。 */
  @Test
  void should_emit_no_cache_markers_when_cache_control_is_none() throws IOException {
    ProviderToolDefinition tool =
        new ProviderToolDefinition("calc", "calculator", "{\"type\":\"object\",\"properties\":{}}");

    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                sysMsg(new ProviderTextBlock("You are helpful")),
                userMsg(new ProviderTextBlock("Hi"))),
            List.of(tool),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    assertFalse(root.path("system").get(0).has("cache_control"));
    assertFalse(root.path("tools").get(0).has("cache_control"));
    assertFalse(root.path("messages").get(0).path("content").get(0).has("cache_control"));
  }

  // =========================================================================================
  // 5. 诊断隔离验证
  // =========================================================================================

  /** 测试意图：正常请求绝不在线缆根节点下包含 diagnostics 字段，因为 Anthropic 请求规范不存在该顶层契约。 */
  @Test
  void should_not_send_diagnostics_when_not_requested() throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("Hi"))),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    assertFalse(root.has("diagnostics"));
  }

  /**
   * 测试意图：区分响应诊断与工具调用诊断；Assistant 回放历史中携带的 ProviderJsonBlock（tool_call_diagnostic） 降级为普通 text
   * 内容块，绝不污染请求根结构注入 diagnostics 字段。
   */
  @Test
  void should_not_confuse_tool_call_diagnostic_with_request_diagnostics() throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                userMsg(new ProviderTextBlock("call tool")),
                asstMsg(
                    new ProviderJsonBlock(
                        "{\"type\":\"tool_call_diagnostic\",\"call_index\":0,\"id\":\"c1\",\"name\":\"fn\",\"message\":\"err\"}"))),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    assertFalse(root.has("diagnostics"));
    JsonNode asstBlock = root.path("messages").get(1).path("content").get(0);
    assertEquals("text", asstBlock.path("type").asText());
    assertTrue(asstBlock.path("text").asText().contains("tool_call_diagnostic"));
  }

  // =========================================================================================
  // 6. 不支持的采样惩罚参数校验
  // =========================================================================================

  /** 测试意图：常规采样参数（温度、top_p、top_k）正常通过校验并不抛出异常。 */
  @Test
  void validate_WithNoUnsupportedFeatures_ShouldNotThrowException() {
    ModelVariant variant =
        new ModelVariant("normal", 1024, 0.7, 0.9, 40, null, null, List.of(), null);
    ProviderRequest request =
        request(
            variant,
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    assertNotNull(encoded);
  }

  /** 测试意图：当设置 frequencyPenalty 时，AnthropicRequestEncoder 必须以 INVALID_REQUEST 确定性拒绝。 */
  @Test
  void validate_WithFrequencyPenalty_ShouldThrowException() {
    ModelVariant variant =
        new ModelVariant("freq", null, null, null, null, 0.5, null, List.of(), null);
    ProviderRequest request =
        request(
            variant,
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException exception =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exception.kind());
    assertTrue(
        exception
            .getMessage()
            .contains("Anthropic does not support frequencyPenalty or presencePenalty"));
  }

  /** 测试意图：当设置 presencePenalty 时，AnthropicRequestEncoder 必须以 INVALID_REQUEST 确定性拒绝。 */
  @Test
  void validate_WithPresencePenalty_ShouldThrowException() {
    ModelVariant variant =
        new ModelVariant("pres", null, null, null, null, null, 0.5, List.of(), null);
    ProviderRequest request =
        request(
            variant,
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException exception =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exception.kind());
    assertTrue(
        exception
            .getMessage()
            .contains("Anthropic does not support frequencyPenalty or presencePenalty"));
  }

  /** 测试意图：当 frequencyPenalty 与 presencePenalty 同时设置时，抛出包含两者不支持说明的 INVALID_REQUEST。 */
  @Test
  void validate_WithTwoUnsupportedFeatures_ShouldThrowExceptionWithCombinedMessage() {
    ModelVariant variant =
        new ModelVariant("both", null, null, null, null, 0.5, 0.5, List.of(), null);
    ProviderRequest request =
        request(
            variant,
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException exception =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exception.kind());
    assertEquals(
        "Anthropic does not support frequencyPenalty or presencePenalty", exception.getMessage());
  }

  // =========================================================================================
  // 7. 会话中 SYSTEM 拦截机制验证
  // =========================================================================================

  /** 测试意图：连续的前置系统消息均被聚合到顶层 system 数组中，messages 数组中不包含任何 SYSTEM 消息。 */
  @Test
  void should_encode_leading_system_messages_in_top_level_system_prompt() throws IOException {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                sysMsg(new ProviderTextBlock("leading-1")),
                sysMsg(new ProviderTextBlock("leading-2")),
                userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(request, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());

    JsonNode systemArray = root.path("system");
    assertEquals(2, systemArray.size());
    assertEquals("leading-1", systemArray.get(0).path("text").asText());
    assertEquals("leading-2", systemArray.get(1).path("text").asText());

    JsonNode messagesArray = root.path("messages");
    assertEquals(1, messagesArray.size());
    assertEquals("user", messagesArray.get(0).path("role").asText());
  }

  /** 测试意图：会话中间出现的 SYSTEM 消息在发起 I/O 前被确定性拦截并抛出 INVALID_REQUEST，不提供内联外壳。 */
  @Test
  void should_reject_mid_conversation_system_message_before_io() {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                sysMsg(new ProviderTextBlock("leading")),
                userMsg(new ProviderTextBlock("hi")),
                sysMsg(new ProviderTextBlock("mid-conversation")),
                userMsg(new ProviderTextBlock("bye"))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException exception =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exception.kind());
    assertEquals(
        "Anthropic does not allow mid-conversation SYSTEM messages", exception.getMessage());
  }

  /** 测试意图：紧跟在工具执行结果（tool_result）之后的会话中 SYSTEM 消息同样被确定性拦截。 */
  @Test
  void should_reject_mid_conversation_system_message_after_tool_result() {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                userMsg(new ProviderTextBlock("calc 2+2")),
                asstToolMsg(new ProviderToolCall("1", "calculator", "{}")),
                toolResultMsg("1", "calculator", false, new ProviderTextBlock("4")),
                sysMsg(new ProviderTextBlock("be concise")),
                userMsg(new ProviderTextBlock("thanks"))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException exception =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exception.kind());
    assertEquals(
        "Anthropic does not allow mid-conversation SYSTEM messages", exception.getMessage());
  }

  /** 测试意图：验证 kk-studio 默认且唯一行为是不支持会话中 SYSTEM 消息，坚决不虚构内联开关外壳。 */
  @Test
  void should_reject_mid_conversation_system_messages_by_default() {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                userMsg(new ProviderTextBlock("User prompt")),
                sysMsg(new ProviderTextBlock("Mid instruction"))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException exception =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exception.kind());
  }

  // =========================================================================================
  // 8. 非法 Schema 与不支持的内容块拒绝校验
  // =========================================================================================

  /** 测试意图：工具 inputSchemaJson 不是合法 JSON 字符串时，抛出 INVALID_REQUEST。 */
  @Test
  void should_reject_malformed_tool_input_schema() {
    ProviderToolDefinition tool = new ProviderToolDefinition("bad_tool", "desc", "{not_valid_json");
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(tool),
            ProviderCacheControl.none());

    ProviderException exception =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exception.kind());
    assertEquals("tool inputSchemaJson is not valid JSON", exception.getMessage());
  }

  /** 测试意图：工具 inputSchemaJson 为 JSON 数组或字面量（非 JSON Object）时，抛出 INVALID_REQUEST。 */
  @Test
  void should_reject_non_object_tool_input_schema() {
    ProviderToolDefinition arrayTool =
        new ProviderToolDefinition("array_tool", "desc", "[\"item\"]");
    ProviderRequest req1 =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(arrayTool),
            ProviderCacheControl.none());

    ProviderException ex1 =
        assertThrows(ProviderException.class, () -> encoder.encode(req1, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex1.kind());
    assertEquals("tool input_schema must be a JSON object", ex1.getMessage());

    ProviderToolDefinition stringTool =
        new ProviderToolDefinition("str_tool", "desc", "\"not_an_object\"");
    ProviderRequest req2 =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(stringTool),
            ProviderCacheControl.none());

    ProviderException ex2 =
        assertThrows(ProviderException.class, () -> encoder.encode(req2, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex2.kind());
    assertEquals("tool input_schema must be a JSON object", ex2.getMessage());
  }

  /** 测试意图：系统消息中出现非 text 且非 document 的不支持块（如图片）时抛出 INVALID_REQUEST。 */
  @Test
  void should_reject_unsupported_system_content_block() {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                sysMsg(new ProviderImageBlock("image/png", DICE_IMAGE_URL)),
                userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException exception =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exception.kind());
    assertEquals("unsupported system content block type", exception.getMessage());
  }

  /** 测试意图：用户消息中出现不支持的内容块（如 thinking 块或 tool call 块）时抛出 INVALID_REQUEST。 */
  @Test
  void should_reject_unsupported_user_content_block() {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderThinkingBlock("thinking"))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException exception =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exception.kind());
    assertEquals("unsupported user content block type", exception.getMessage());
  }

  /** 测试意图：图片块携带不受支持的 MIME 类型（如 image/bmp）时抛出 INVALID_REQUEST。 */
  @Test
  void should_reject_unsupported_image_media_type() {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderImageBlock("image/bmp", "https://example.com/a.bmp"))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException exception =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exception.kind());
    assertEquals("unsupported image media type", exception.getMessage());
  }

  /** 测试意图：图片源既非合法的 http/https URL 也非匹配的 Base64 data URI 时抛出 INVALID_REQUEST。 */
  @Test
  void should_reject_invalid_image_source() {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderImageBlock("image/png", "ftp://example.com/invalid.png"))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException exception =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exception.kind());
    assertEquals(
        "image source must be a valid http(s) URL or base64 data URI matching mediaType",
        exception.getMessage());
  }

  /** 测试意图：文档块携带不受支持的 MIME 类型（非 application/pdf）时抛出 INVALID_REQUEST。 */
  @Test
  void should_reject_unsupported_document_media_type() {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                userMsg(
                    new ProviderDocumentBlock(
                        "text/plain", "data:text/plain;base64," + BASE64_IMAGE_DATA))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException exception =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exception.kind());
    assertEquals("unsupported document media type", exception.getMessage());
  }

  /** 测试意图：文档块未采用 base64 data URI 格式（如使用了普通 http URL）时抛出 INVALID_REQUEST。 */
  @Test
  void should_reject_non_base64_document_source() {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                userMsg(
                    new ProviderDocumentBlock(
                        "application/pdf", "https://example.com/report.pdf"))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException exception =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exception.kind());
    assertEquals(
        "document source must be a valid base64 data URI matching mediaType",
        exception.getMessage());
  }

  /** 测试意图：TOOL 角色消息在构造阶段严格校验必须且仅能包含单条 ProviderToolResultBlock。 */
  @Test
  void should_reject_non_tool_result_in_tool_message() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ProviderMessage(
                    ProviderMessageRole.TOOL, List.of(new ProviderTextBlock("not a tool result"))));
    assertTrue(
        exception
            .getMessage()
            .contains("TOOL messages must contain exactly one provider tool result block"));
  }

  /** 测试意图：tool_result 内部嵌套了不受支持的内容块（如 thinking 块）时抛出 INVALID_REQUEST。 */
  @Test
  void should_reject_unsupported_content_block_inside_tool_result() {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                userMsg(new ProviderTextBlock("call")),
                asstToolMsg(new ProviderToolCall("1", "calc", "{}")),
                toolMsg(
                    new ProviderToolResultBlock(
                        "1",
                        "calc",
                        List.of(new ProviderThinkingBlock("illegal inside tool result")),
                        false,
                        "{}"))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException exception =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exception.kind());
    assertEquals("unsupported content block inside tool_result", exception.getMessage());
  }

  /** 测试意图：Assistant 消息回放降级分支遇到不受支持的内容块（如图片）时抛出 INVALID_REQUEST。 */
  @Test
  void should_reject_unsupported_assistant_content_block() {
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(
                userMsg(new ProviderTextBlock("call")),
                asstMsg(new ProviderImageBlock("image/png", DICE_IMAGE_URL))),
            List.of(),
            ProviderCacheControl.none());

    ProviderException exception =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exception.kind());
    assertEquals("unsupported assistant block type", exception.getMessage());
  }

  /** 测试意图：当启用缓存（retention 非 NONE）但 breakpoints 集合为空时，确定性抛出 INVALID_REQUEST。 */
  @Test
  void should_reject_cache_control_without_breakpoints_when_retention_is_active() {
    // 使用 affinity 构造一个没有 breakpoints 的缓存控制指令传入 encoder
    ProviderRequest request =
        request(
            defaultVariant(),
            List.of(userMsg(new ProviderTextBlock("hi"))),
            List.of(),
            ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "aff-no-breakpoints"));

    ProviderException exception =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exception.kind());
    assertEquals(
        "Anthropic prompt cache control requires at least one breakpoint (SYSTEM, TOOLS, CONVERSATION)",
        exception.getMessage());
  }

  // =========================================================================================
  // 辅助方法与数据结构
  // =========================================================================================

  record MessageMappingCase(
      String description,
      List<ProviderMessage> messages,
      String expectedMessagesJson,
      String expectedSystemJson,
      ProviderErrorKind expectedErrorKind,
      String expectedErrorMessageSubstring) {

    static MessageMappingCase success(
        String description,
        List<ProviderMessage> messages,
        String expectedMessagesJson,
        String expectedSystemJson) {
      return new MessageMappingCase(
          description, messages, expectedMessagesJson, expectedSystemJson, null, null);
    }

    static MessageMappingCase failure(
        String description,
        List<ProviderMessage> messages,
        ProviderErrorKind expectedErrorKind,
        String expectedErrorMessageSubstring) {
      return new MessageMappingCase(
          description, messages, null, null, expectedErrorKind, expectedErrorMessageSubstring);
    }

    @Override
    public String toString() {
      return description;
    }
  }

  private static String loadFixture(String fixtureName) {
    String path = "/fun/fengwk/kkstudio/harness/provider/anthropic/fixtures/" + fixtureName;
    try (InputStream is = AnthropicRequestMapperTest.class.getResourceAsStream(path)) {
      if (is == null) {
        throw new IllegalStateException("Fixture resource not found: " + path);
      }
      return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new RuntimeException("Failed to read fixture: " + path, exception);
    }
  }

  private static ModelVariant defaultVariant() {
    return new ModelVariant("default", 1024, null, null, null, null, null, List.of(), null);
  }

  private static ProviderRequest request(
      ModelVariant variant,
      List<ProviderMessage> messages,
      List<ProviderToolDefinition> tools,
      ProviderCacheControl cacheControl) {
    ModelDescriptor model =
        new ModelDescriptor(
            "test-anthropic",
            "claude-3-5-sonnet",
            Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE, ModelInputModality.DOCUMENT),
            true,
            false,
            pricing());
    return new ProviderRequest(model, variant, messages, tools, cacheControl);
  }

  private static ModelPricing pricing() {
    return new ModelPricing(
        "USD",
        "tier-1",
        "default",
        BigDecimal.ONE,
        "v1",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }

  private static ProviderMessage userMsg(ProviderContentBlock... blocks) {
    return new ProviderMessage(ProviderMessageRole.USER, List.of(blocks));
  }

  private static ProviderMessage sysMsg(ProviderContentBlock... blocks) {
    return new ProviderMessage(ProviderMessageRole.SYSTEM, List.of(blocks));
  }

  private static ProviderMessage asstMsg(ProviderContentBlock... blocks) {
    return new ProviderMessage(ProviderMessageRole.ASSISTANT, List.of(blocks));
  }

  private static ProviderMessage asstToolMsg(ProviderToolCall... toolCalls) {
    List<ProviderContentBlock> blocks =
        Stream.of(toolCalls)
            .map(tc -> (ProviderContentBlock) new ProviderToolCallBlock(tc))
            .toList();
    return new ProviderMessage(ProviderMessageRole.ASSISTANT, blocks);
  }

  private static ProviderMessage toolMsg(ProviderContentBlock... blocks) {
    return new ProviderMessage(ProviderMessageRole.TOOL, List.of(blocks));
  }

  private static ProviderMessage toolResultMsg(
      String toolCallId, String toolName, boolean isError, ProviderContentBlock... blocks) {
    return new ProviderMessage(
        ProviderMessageRole.TOOL,
        List.of(new ProviderToolResultBlock(toolCallId, toolName, List.of(blocks), isError, "{}")));
  }
}
