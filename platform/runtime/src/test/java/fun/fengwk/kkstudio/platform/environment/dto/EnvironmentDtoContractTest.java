package fun.fengwk.kkstudio.platform.environment.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

import fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;

/** Environment wire 契约：字段名、封闭枚举取值与显式 null 的稳定形状。 */
class EnvironmentDtoContractTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** 无连接时仍显式给出 null 截止时间；其余可空字段与未下发的 token 按 NON_NULL 策略省略。 */
  @Test
  void offlineEnvironmentKeepsNullStatusDeadlineUnderNonNullWirePolicy() throws Exception {
    EnvironmentDTO environment = new EnvironmentDTO();
    environment.setStatus(EnvironmentStatus.OFFLINE);
    JsonMapper mapper =
        JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();
    JsonNode json = mapper.readTree(mapper.writeValueAsString(environment));
    assertTrue(json.has("statusExpiresAt"));
    assertTrue(json.get("statusExpiresAt").isNull());
    assertTrue(!json.has("registrationToken"));
    assertTrue(!json.has("lastSeenAt"));
    assertTrue(!json.has("operatingSystem"));
  }

  /** 时间字段统一 *At：createdAt/updatedAt/lastSeenAt/occurredAt 是唯一 wire 名称。 */
  @Test
  void environmentTimesUseAtSuffix() throws Exception {
    EnvironmentDTO environment = new EnvironmentDTO();
    environment.setCreatedAt(Instant.EPOCH);
    environment.setUpdatedAt(Instant.EPOCH);
    environment.setLastSeenAt(Instant.EPOCH);
    JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(environment));

    assertTrue(json.has("createdAt"));
    assertTrue(json.has("updatedAt"));
    assertTrue(json.has("lastSeenAt"));
    assertTrue(!json.has("createTime") && !json.has("updateTime") && !json.has("lastSeen"));
  }

  /** 固定词汇是封闭枚举：status、事件 level/type 与命令状态按常量名输出，操作系统按小写 wire 值输出。 */
  @Test
  void closedVocabulariesSerializeToStableWireValues() {
    EnvironmentDTO environment = new EnvironmentDTO();
    environment.setStatus(EnvironmentStatus.READY);
    environment.setOperatingSystem(DaemonOperatingSystem.WSL);
    JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(environment));
    assertEquals("READY", json.get("status").stringValue());
    assertEquals("wsl", json.get("operatingSystem").stringValue());

    EnvironmentEventDTO event = new EnvironmentEventDTO();
    event.setLevel(EnvironmentEventLevel.WARN);
    event.setType(EnvironmentEventType.SKILL_SYNC_FAILED);
    event.setOccurredAt(Instant.EPOCH);
    JsonNode eventJson = MAPPER.readTree(MAPPER.writeValueAsString(event));
    assertEquals("WARN", eventJson.get("level").stringValue());
    assertEquals("SKILL_SYNC_FAILED", eventJson.get("type").stringValue());
    assertTrue(eventJson.has("occurredAt"));
    assertTrue(!eventJson.has("time"));

    EnvironmentUpdateStartDTO started = new EnvironmentUpdateStartDTO();
    started.setCommandStatus(EnvironmentUpdateCommandStatus.SENT_UNCONFIRMED);
    JsonNode startedJson = MAPPER.readTree(MAPPER.writeValueAsString(started));
    assertEquals("SENT_UNCONFIRMED", startedJson.get("commandStatus").stringValue());
  }

  /** 操作系统枚举覆盖四个 wire 值，且只接受小写 wire 解析。 */
  @Test
  void operatingSystemEnumMatchesDaemonWireVocabulary() {
    assertEquals(
        List.of("windows", "wsl", "linux", "macos"),
        List.of(
                DaemonOperatingSystem.WINDOWS,
                DaemonOperatingSystem.WSL,
                DaemonOperatingSystem.LINUX,
                DaemonOperatingSystem.MACOS)
            .stream()
            .map(DaemonOperatingSystem::wireValue)
            .toList());
    assertEquals(DaemonOperatingSystem.MACOS, DaemonOperatingSystem.fromWireValue("macos"));
  }

  /** 意图：Environment 暴露 Daemon 进程用户与 HOME，二者都是可空字符串。 */
  @Test
  void environmentExposesHostUserAndHome() throws Exception {
    assertEquals(String.class, EnvironmentDTO.class.getDeclaredField("userName").getType());
    assertEquals(String.class, EnvironmentDTO.class.getDeclaredField("homeDirectory").getType());
  }

  /** 意图：Environment 同时暴露实际 daemon 构建版本与 CAS 配置版本，二者是不同事实、不能互相推导。 */
  @Test
  void environmentExposesDaemonVersionDistinctFromCasVersion() throws Exception {
    assertEquals(String.class, EnvironmentDTO.class.getDeclaredField("daemonVersion").getType());
    assertEquals(Long.class, EnvironmentDTO.class.getDeclaredField("version").getType());
  }

  /** 意图：runtime wire 保留 revision/status，且无更新与未知总长都必须显式输出 null。 */
  @Test
  void runtimeStatusExplicitlySerializesNullableUpdateAndTotalBytes() {
    EnvironmentRuntimeDTO status = new EnvironmentRuntimeDTO();
    status.setDaemonVersion("1.0.9");
    status.setUpdate(null);
    EnvironmentRuntimeStatusDTO snapshot = new EnvironmentRuntimeStatusDTO();
    snapshot.setRevision(1L);
    snapshot.setStatus(status);

    SimpleModule longs = new SimpleModule("http-longs");
    longs.addSerializer(Long.class, new ToStringSerializer(Long.class));
    longs.addSerializer(Long.TYPE, new ToStringSerializer(Long.TYPE));
    JsonMapper httpMapper = MAPPER.rebuild().addModule(longs).build();
    JsonNode json = httpMapper.readTree(httpMapper.writeValueAsString(snapshot));

    assertEquals("1", json.get("revision").stringValue());
    assertEquals("1.0.9", json.get("status").get("daemonVersion").stringValue());
    assertTrue(json.get("status").has("update"));
    assertTrue(json.get("status").get("update").isNull());

    EnvironmentUpdateProgressDTO progress = new EnvironmentUpdateProgressDTO();
    progress.setTargetVersion("1.0.10");
    progress.setDownloadedBytes(12L);
    progress.setTotalBytes(null);
    status.setUpdate(progress);
    JsonNode withProgress = httpMapper.readTree(httpMapper.writeValueAsString(snapshot));
    assertTrue(withProgress.get("status").get("update").has("totalBytes"));
    assertTrue(withProgress.get("status").get("update").get("totalBytes").isNull());
  }

  /** 意图：start update 响应只有运行快照与命令状态两项，命令状态是封闭枚举。 */
  @Test
  void updateStartResponseCarriesCommandStatus() {
    assertEquals(
        List.of("runtimeStatus", "commandStatus"),
        List.of(EnvironmentUpdateStartDTO.class.getDeclaredFields()).stream()
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .map(Field::getName)
            .toList());
    assertEquals(
        List.of("CURRENT", "IN_PROGRESS", "SENT_UNCONFIRMED"),
        List.of(EnvironmentUpdateCommandStatus.values()).stream().map(Enum::name).toList());
  }

  /** 意图：事件是有界的四项事实，时间字段为 occurredAt。 */
  @Test
  void eventsStayBoundedFacts() throws Exception {
    assertEquals(
        List.of("occurredAt", "level", "type", "message"),
        List.of(EnvironmentEventDTO.class.getDeclaredFields()).stream()
            .map(Field::getName)
            .toList());

    assertEquals(Instant.class, EnvironmentEventDTO.class.getDeclaredField("occurredAt").getType());
  }
}
