package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 意图：生产 Java 制品 API 与 UPDATE 命令 codec 必须与跨语言共享 fixture {@code
 * daemon/test/resources/update/official-artifact-fixtures.json} 逐字一致——五个已发布平台的
 * artifactName/URL/checksum 由 生产 API 生成，正例 payload 经真实 codec 编解码往返，全部反例被真实 codec 拒绝。
 *
 * <p>fixture 是唯一事实源：测试逐条读取 accepted/rejected 条目，不另抄 URL 清单。除断言外，测试还把实际生产 Java 编码结果写到 {@code
 * target/daemon-update-java-commands.json}（本模块 target，不进入版本库），供父随后用真实 Node decoder 串联；本测试不依赖 Node、
 * 不新增环境开关，也不跳过。
 */
class OfficialUpdateArtifactFixtureTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  /** 官方发布矩阵规模：五个平台；fixture 必须完整覆盖。 */
  private static final int PUBLISHED_PLATFORM_COUNT = 5;

  /** 反例清单规模：fixture 必须覆盖每一种已知的错配形态。 */
  private static final int REJECTION_FORM_COUNT = 12;

  /**
   * UPDATE 信封要求绑定 Environment scope，而共享 fixture 只描述制品与命令 payload。这里用一个明确的占位 scope 组装信封；
   * 它不属于制品/命令契约，跨语言消费方只解析 {@code payload}（即 {@code commandJson}）。
   */
  private static final String PLACEHOLDER_ENVIRONMENT_ID = "00000000-0000-0000-0000-000000000000";

  private final DaemonUpdateCommandCodec commandCodec = new DaemonUpdateCommandCodec();
  private final DaemonEnvelopeCodec envelopeCodec = new DaemonEnvelopeCodec();

  @Test
  void acceptedArtifactsComeFromTheProductionApiAndRoundTripThroughTheCodec() throws IOException {
    JsonNode fixture = readFixture();
    List<String> names = new ArrayList<>();

    for (JsonNode entry : fixture.get("accepted")) {
      DaemonUpdateCommand command = acceptedCommand(fixture, entry);
      names.add(entry.get("name").stringValue());
      assertEquals(
          command,
          commandCodec.decode(commandCodec.encode(command)),
          "the production codec must round-trip the accepted command: " + command.artifactUrl());
    }

    assertEquals(
        PUBLISHED_PLATFORM_COUNT,
        names.size(),
        "the fixture must cover the five published platforms: " + names);
  }

  @Test
  void rejectedArtifactsAreRefusedByTheProductionCodec() throws IOException {
    JsonNode fixture = readFixture();
    List<String> names = new ArrayList<>();

    for (JsonNode entry : fixture.get("rejected")) {
      assertThrows(
          DaemonProtocolException.class,
          () -> commandCodec.decode(rejectedPayload(fixture, entry)),
          "the production codec must refuse the rejected artifact: " + entry.get("name"));
      names.add(entry.get("name").stringValue());
    }

    assertEquals(
        REJECTION_FORM_COUNT,
        names.size(),
        "the fixture must cover every documented rejection form: " + names);
  }

  /**
   * 生成跨语言串联产物：正例 commandJson/envelopeJson 来自生产 codec（envelope 为 wire v4 UPDATE），反例保留按 fixture 组装的原始
   * payload。产物只写本模块 {@code target}，供父用真实 Node decoder 验证「Java 接受 ⇔ Node 接受」。
   */
  @Test
  void writesJavaProductionCommandsForCrossLanguageReplay() throws IOException {
    JsonNode fixture = readFixture();
    ObjectNode artifact = MAPPER.createObjectNode();
    ArrayNode accepted = artifact.putArray("accepted");
    ArrayNode rejected = artifact.putArray("rejected");

    for (JsonNode entry : fixture.get("accepted")) {
      DaemonUpdateCommand command = acceptedCommand(fixture, entry);
      String commandJson = commandCodec.encode(command);
      String envelopeJson =
          envelopeCodec.encode(
              new DaemonEnvelope(
                  DaemonProtocol.VERSION,
                  DaemonMessageType.UPDATE,
                  EnvironmentId.parse(PLACEHOLDER_ENVIRONMENT_ID),
                  null,
                  commandJson));
      DaemonEnvelope decodedEnvelope = envelopeCodec.decode(envelopeJson);
      assertEquals(DaemonProtocol.VERSION, decodedEnvelope.protocolVersion());
      assertEquals(DaemonMessageType.UPDATE, decodedEnvelope.messageType());
      assertEquals(
          MAPPER.readTree(commandJson),
          MAPPER.readTree(decodedEnvelope.payloadJson()),
          "the UPDATE envelope must carry the production command payload unchanged");
      ObjectNode record = accepted.addObject();
      record.put("name", entry.get("name").stringValue());
      record.put("commandJson", commandJson);
      record.put("envelopeJson", envelopeJson);
    }

    for (JsonNode entry : fixture.get("rejected")) {
      String payload = rejectedPayload(fixture, entry);
      assertThrows(
          DaemonProtocolException.class,
          () -> commandCodec.decode(payload),
          "the production codec must refuse the rejected artifact: " + entry.get("name"));
      ObjectNode record = rejected.addObject();
      record.put("name", entry.get("name").stringValue());
      record.put("commandJson", payload);
    }

    Path target = Path.of("target", "daemon-update-java-commands.json");
    Files.createDirectories(target.getParent());
    Files.writeString(target, MAPPER.writeValueAsString(artifact), StandardCharsets.UTF_8);
    assertTrue(
        Files.exists(target), "the cross-language artifact must be written to the module target");

    JsonNode written = MAPPER.readTree(Files.readString(target, StandardCharsets.UTF_8));
    assertEquals(fixture.get("accepted").size(), written.get("accepted").size());
    assertEquals(fixture.get("rejected").size(), written.get("rejected").size());
  }

  /** 生产 API 必须逐字生成 fixture 的 artifactName/URL/checksum，并接受同一版本事实的 command。 */
  private DaemonUpdateCommand acceptedCommand(JsonNode fixture, JsonNode entry) {
    String version = fixture.get("targetVersion").stringValue();
    DaemonOperatingSystem operatingSystem =
        DaemonOperatingSystem.fromWireValue(entry.get("operatingSystem").stringValue());
    DaemonArchitecture architecture =
        DaemonArchitecture.fromWireValue(entry.get("architecture").stringValue());

    assertEquals(
        entry.get("artifactFileName").stringValue(),
        DaemonUpdateArtifact.artifactName(version, operatingSystem, architecture),
        "artifactName must match the fixture entry: " + entry.get("name"));
    assertEquals(
        entry.get("artifactUrl").stringValue(),
        DaemonUpdateArtifact.artifactUrl(version, operatingSystem, architecture),
        "artifactUrl must match the fixture entry: " + entry.get("name"));
    assertEquals(
        entry.get("checksumUrl").stringValue(),
        DaemonUpdateArtifact.checksumUrl(version, operatingSystem, architecture),
        "checksumUrl must match the fixture entry: " + entry.get("name"));

    return new DaemonUpdateCommand(
        fixture.get("operationId").stringValue(),
        version,
        entry.get("artifactUrl").stringValue(),
        fixture.get("artifactSha256").stringValue());
  }

  /** 按 fixture 组装一条「其余字段合法、仅 artifactUrl 是反例」的原始 payload，交由生产 codec 拒绝。 */
  private static String rejectedPayload(JsonNode fixture, JsonNode entry) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("operationId", fixture.get("operationId").stringValue());
    payload.put("targetVersion", fixture.get("targetVersion").stringValue());
    payload.put("artifactUrl", entry.get("artifactUrl").stringValue());
    payload.put("artifactSha256", fixture.get("artifactSha256").stringValue());
    return MAPPER.writeValueAsString(payload);
  }

  private static JsonNode readFixture() throws IOException {
    Path fixture = fixturePath();
    return MAPPER.readTree(Files.readString(fixture, StandardCharsets.UTF_8));
  }

  /** fixture 属于跨语言共享的 daemon 测试资源：从工作目录向上定位仓库根；缺失即失败，绝不跳过。 */
  private static Path fixturePath() {
    Path relative =
        Path.of("daemon", "test", "resources", "update", "official-artifact-fixtures.json");
    Path directory = Path.of("").toAbsolutePath();
    for (int depth = 0; depth < 6 && directory != null; depth++) {
      Path candidate = directory.resolve(relative);
      if (Files.exists(candidate)) {
        return candidate;
      }
      directory = directory.getParent();
    }
    throw new IllegalStateException(
        "shared update artifact fixture not found; expected daemon/test/resources/update/"
            + "official-artifact-fixtures.json in an ancestor of "
            + Path.of("").toAbsolutePath());
  }
}
