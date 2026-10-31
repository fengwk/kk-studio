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

import fun.fengwk.kkstudio.harness.environment.protocol.ControlProtocolCodec;
import fun.fengwk.kkstudio.harness.environment.protocol.ControlProtocolException;
import fun.fengwk.kkstudio.harness.environment.protocol.Envelope;
import fun.fengwk.kkstudio.harness.environment.protocol.MessageType;
import fun.fengwk.kkstudio.harness.environment.protocol.Update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 意图：生产 Java 制品 API 与 UPDATE 命令必须与跨语言共享 fixture {@code
 * daemon/test/resources/update/official-artifact-fixtures.json} 逐字一致——五个已发布平台的
 * artifactName/URL/checksum 由 生产 API 生成，正例 payload 经真实 proto 编解码往返，全部反例被真实校验拒绝。
 */
class OfficialUpdateArtifactFixtureTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  /** 官方发布矩阵规模：五个平台；fixture 必须完整覆盖。 */
  private static final int PUBLISHED_PLATFORM_COUNT = 5;

  /** 反例清单规模：fixture 必须覆盖每一种已知的错配形态。 */
  private static final int REJECTION_FORM_COUNT = 12;

  private static final String PLACEHOLDER_ENVIRONMENT_ID = "00000000-0000-0000-0000-000000000000";

  @Test
  void acceptedArtifactsComeFromTheProductionApiAndRoundTripThroughTheCodec() throws IOException {
    JsonNode fixture = readFixture();
    List<String> names = new ArrayList<>();

    for (JsonNode entry : fixture.get("accepted")) {
      DaemonUpdateCommand command = acceptedCommand(fixture, entry);
      names.add(entry.get("name").stringValue());
      Update proto = ControlProtocolCodec.toProtoUpdate(command);
      ControlProtocolCodec.validateUpdate(proto);
      DaemonUpdateCommand roundTrip = ControlProtocolCodec.toDomainUpdate(proto);
      assertEquals(command, roundTrip);
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
          ControlProtocolException.class,
          () -> {
            DaemonUpdateCommand command =
                new DaemonUpdateCommand(
                    fixture.get("operationId").stringValue(),
                    fixture.get("targetVersion").stringValue(),
                    entry.get("artifactUrl").stringValue(),
                    fixture.get("artifactSha256").stringValue());
            Update proto = ControlProtocolCodec.toProtoUpdate(command);
            ControlProtocolCodec.validateUpdate(proto);
          },
          "the production codec must refuse the rejected artifact: " + entry.get("name"));
      names.add(entry.get("name").stringValue());
    }

    assertEquals(
        REJECTION_FORM_COUNT,
        names.size(),
        "the fixture must cover every documented rejection form: " + names);
  }

  @Test
  void writesJavaProductionCommandsForCrossLanguageReplay() throws IOException {
    JsonNode fixture = readFixture();
    ObjectNode artifact = MAPPER.createObjectNode();
    ArrayNode accepted = artifact.putArray("accepted");
    ArrayNode rejected = artifact.putArray("rejected");

    for (JsonNode entry : fixture.get("accepted")) {
      DaemonUpdateCommand command = acceptedCommand(fixture, entry);
      Envelope envelope =
          Envelope.newBuilder()
              .setMessageType(MessageType.UPDATE)
              .setEnvironmentId(PLACEHOLDER_ENVIRONMENT_ID)
              .setUpdate(ControlProtocolCodec.toProtoUpdate(command))
              .build();
      byte[] frame = ControlProtocolCodec.encodeFrame(envelope);
      Envelope decoded = ControlProtocolCodec.decodeFrame(frame);
      assertEquals(MessageType.UPDATE, decoded.getMessageType());
      assertEquals(PLACEHOLDER_ENVIRONMENT_ID, decoded.getEnvironmentId());
      DaemonUpdateCommand decodedCommand = ControlProtocolCodec.toDomainUpdate(decoded.getUpdate());
      assertEquals(command, decodedCommand);

      ObjectNode record = accepted.addObject();
      record.put("name", entry.get("name").stringValue());
      record.put("envelopeHex", HexFormat.of().formatHex(frame));
    }

    for (JsonNode entry : fixture.get("rejected")) {
      ObjectNode record = rejected.addObject();
      record.put("name", entry.get("name").stringValue());
      record.put("artifactUrl", entry.get("artifactUrl").stringValue());
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

  private static JsonNode readFixture() throws IOException {
    Path fixture = fixturePath();
    return MAPPER.readTree(Files.readString(fixture, StandardCharsets.UTF_8));
  }

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
