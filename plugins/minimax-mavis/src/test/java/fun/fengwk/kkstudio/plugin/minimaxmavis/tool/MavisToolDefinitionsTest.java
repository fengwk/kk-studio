package fun.fengwk.kkstudio.plugin.minimaxmavis.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.plugin.minimaxmavis.MavisCapability;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 15 项 Tool 静态资源的加载与严格校验。
 *
 * <p>schema 是模型可见契约，必须能通过 Harness 的严格 codec 与构造约束，并与能力枚举一一对应（不多不少）；同时逐项锁定 required 字段与 canonical
 * 编码稳定性，避免后续维护悄悄放宽 schema 或改变 Prompt Cache 前缀。
 */
class MavisToolDefinitionsTest {

  private static final String RESOURCE_DIRECTORY = "fun/fengwk/kkstudio/plugin/minimaxmavis/tools/";

  private static final SchemaJsonCodec CODEC = new SchemaJsonCodec();

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Map<MavisCapability, Set<String>> EXPECTED_REQUIRED = expectedRequired();

  private static Map<MavisCapability, Set<String>> expectedRequired() {
    Map<MavisCapability, Set<String>> required = new EnumMap<>(MavisCapability.class);
    required.put(MavisCapability.WEB_SEARCH, Set.of("query"));
    required.put(MavisCapability.EXTRACT_WEB, Set.of("prompt", "urls"));
    required.put(MavisCapability.IMAGE_SEARCH, Set.of("queries"));
    required.put(MavisCapability.REVERSE_IMAGE, Set.of("image"));
    required.put(MavisCapability.UNDERSTAND_IMAGE, Set.of("inputs"));
    required.put(MavisCapability.UNDERSTAND_AUDIO, Set.of("inputs"));
    required.put(MavisCapability.UNDERSTAND_VIDEO, Set.of("inputs"));
    required.put(MavisCapability.ASR, Set.of("input"));
    required.put(MavisCapability.LIST_VOICES, Set.of());
    required.put(MavisCapability.TTS, Set.of("text"));
    required.put(MavisCapability.TTS_BATCH, Set.of("requests"));
    required.put(MavisCapability.GENERATE_IMAGE, Set.of("prompt"));
    required.put(MavisCapability.GENERATE_MUSIC, Set.of("prompt"));
    required.put(MavisCapability.SUBMIT_VIDEO, Set.of("duration", "model", "prompt"));
    required.put(MavisCapability.QUERY_VIDEO, Set.of("model", "task_id"));
    return required;
  }

  /** 15 项能力全部有工具定义，名称与枚举派生一致且是合法模型工具名。 */
  @Test
  void loadsEveryCapabilityWithStableToolName() {
    Map<MavisCapability, MavisToolDefinition> definitions = MavisToolDefinitions.loadAll();

    assertEquals(MavisCapability.values().length, definitions.size());
    for (MavisCapability capability : MavisCapability.values()) {
      MavisToolDefinition definition = definitions.get(capability);
      assertEquals(capability, definition.capability());
      assertEquals(capability.toolName(), definition.name());
      assertTrue(ToolDescriptor.isValidName(definition.name()));
      assertFalse(definition.description().isBlank());
      assertFalse(definition.inputSchema().additionalProperties());
    }
  }

  /** 每个能力的 required 字段与线上参数契约一致。 */
  @Test
  void locksRequiredFieldsPerCapability() {
    for (MavisCapability capability : MavisCapability.values()) {
      InputSchema schema = MavisToolDefinitions.load(capability).inputSchema();

      assertEquals(
          EXPECTED_REQUIRED.get(capability),
          schema.required(),
          "required fields changed for " + capability.toolName());
    }
  }

  /** 每个 schema 的 canonical 编码可以无损往返，保证交给模型的 schema 与 Prompt Cache 前缀稳定。 */
  @Test
  void schemasAreStableUnderCanonicalEncoding() {
    for (MavisCapability capability : MavisCapability.values()) {
      String canonical = CODEC.encode(MavisToolDefinitions.load(capability).inputSchema());

      assertEquals(MavisToolDefinitions.load(capability).inputSchema(), CODEC.decode(canonical));
    }
  }

  /** 资源目录与能力枚举一一对应：没有多余文件，也没有缺失文件。 */
  @Test
  void resourceDirectoryMatchesCapabilitySet() throws IOException {
    Path directory = resourceDirectory();
    List<String> resources = new ArrayList<>();
    try (var stream = Files.list(directory)) {
      stream
          .filter(Files::isRegularFile)
          .map(file -> file.getFileName().toString())
          .sorted()
          .forEach(resources::add);
    }

    List<String> expected =
        Arrays.stream(MavisCapability.values())
            .map(capability -> capability.id() + ".json")
            .sorted()
            .toList();
    assertEquals(expected, resources);
  }

  /** 资源文件本身是严格 JSON，只声明 description 与 inputSchema。 */
  @Test
  void resourceContentDeclaresOnlyDescriptionAndSchema() throws IOException {
    for (MavisCapability capability : MavisCapability.values()) {
      String resource = RESOURCE_DIRECTORY + capability.id() + ".json";
      String content;
      try (InputStream stream =
          MavisToolDefinitionsTest.class.getClassLoader().getResourceAsStream(resource)) {
        content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      }

      assertTrue(content.contains("\"description\""));
      assertTrue(content.contains("\"inputSchema\""));
      assertEquals(2, MAPPER.readTree(content).size(), resource + " declares unexpected fields");
    }
  }

  /** 非法资源内容在启动期失败：非 JSON、未知字段、缺失或空 description、缺失或非法 schema 都被拒绝。 */
  @Test
  void rejectsInvalidResourceContent() {
    assertThrows(
        IllegalStateException.class,
        () -> MavisToolDefinitions.decode(MavisCapability.WEB_SEARCH, "not json", "ctx"));
    assertThrows(
        IllegalStateException.class,
        () -> MavisToolDefinitions.decode(MavisCapability.WEB_SEARCH, "[1]", "ctx"));
    assertThrows(
        IllegalStateException.class,
        () ->
            MavisToolDefinitions.decode(
                MavisCapability.WEB_SEARCH,
                "{\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"},\"extra\":1}",
                "ctx"));
    assertThrows(
        IllegalStateException.class,
        () ->
            MavisToolDefinitions.decode(
                MavisCapability.WEB_SEARCH, "{\"inputSchema\":{\"type\":\"object\"}}", "ctx"));
    assertThrows(
        IllegalStateException.class,
        () ->
            MavisToolDefinitions.decode(
                MavisCapability.WEB_SEARCH,
                "{\"description\":\"  \",\"inputSchema\":{\"type\":\"object\"}}",
                "ctx"));
    assertThrows(
        IllegalStateException.class,
        () ->
            MavisToolDefinitions.decode(
                MavisCapability.WEB_SEARCH, "{\"description\":\"d\"}", "ctx"));
    assertThrows(
        IllegalStateException.class,
        () ->
            MavisToolDefinitions.decode(
                MavisCapability.WEB_SEARCH,
                "{\"description\":\"d\",\"inputSchema\":{\"type\":\"object\",\"properties\":{},"
                    + "\"required\":[\"missing\"],\"additionalProperties\":false}}",
                "ctx"));
  }

  private static Path resourceDirectory() throws IOException {
    var url = MavisToolDefinitionsTest.class.getClassLoader().getResource(RESOURCE_DIRECTORY);
    if (url == null) {
      throw new IllegalStateException("missing tool resource directory: " + RESOURCE_DIRECTORY);
    }
    return Path.of(url.getPath());
  }
}
