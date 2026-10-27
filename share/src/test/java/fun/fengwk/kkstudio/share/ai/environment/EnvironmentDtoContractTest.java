package fun.fengwk.kkstudio.share.ai.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;

/** 验证 Environment 相关 DTO 的序列化、反序列化以及未知字段严格校验契约。 */
class EnvironmentDtoContractTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** 无连接时仍显式给出 null 截止时间，客户端不以缺失字段猜测连接状态。 */
  @Test
  void offlineCardKeepsNullStatusDeadlineUnderNonNullWirePolicy() throws Exception {
    EnvironmentCardDTO card = new EnvironmentCardDTO();
    card.setStatus("OFFLINE");
    JsonMapper mapper =
        JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();
    JsonNode json = mapper.readTree(mapper.writeValueAsString(card));
    assertTrue(json.has("statusExpiresAt"));
    assertTrue(json.get("statusExpiresAt").isNull());
  }

  /** 意图：验证 EnvironmentCardDTO 已彻底移除 flat skills 字段以及对应的 getter/setter 访问器契约。 */
  @Test
  void environmentCardDtoHasNoSkillsFieldOrAccessors() {
    assertThrows(
        NoSuchFieldException.class, () -> EnvironmentCardDTO.class.getDeclaredField("skills"));
    assertThrows(
        NoSuchMethodException.class, () -> EnvironmentCardDTO.class.getMethod("getSkills"));
    assertThrows(
        NoSuchMethodException.class,
        () -> EnvironmentCardDTO.class.getMethod("setSkills", List.class));
  }

  /** 意图：验证 EnvironmentCardDTO 用 Daemon 进程用户与 HOME 替换旧 rootPath（后者不再是 cwd 或默认 workdir）。 */
  @Test
  void environmentCardDtoExposesHostUserAndHomeInsteadOfRootPath() throws Exception {
    assertThrows(
        NoSuchFieldException.class, () -> EnvironmentCardDTO.class.getDeclaredField("rootPath"));
    assertEquals(String.class, EnvironmentCardDTO.class.getDeclaredField("userName").getType());
    assertEquals(
        String.class, EnvironmentCardDTO.class.getDeclaredField("homeDirectory").getType());
  }

  /** 意图：Card 同时暴露实际 daemon 构建版本与 CAS 配置版本，二者是不同事实、不能互相推导。 */
  @Test
  void environmentCardExposesDaemonVersionDistinctFromCasVersion() throws Exception {
    assertEquals(
        String.class, EnvironmentCardDTO.class.getDeclaredField("daemonVersion").getType());
    assertEquals(String.class, EnvironmentCardDTO.class.getDeclaredField("version").getType());
  }

  /** 意图：Card 不再携带历史更新任务，当前运行信息只经 runtime 端点投影。 */
  @Test
  void environmentCardCarriesNoManagedUpdateProjection() throws Exception {
    assertThrows(
        NoSuchFieldException.class, () -> EnvironmentCardDTO.class.getDeclaredField("update"));
    assertThrows(
        NoSuchMethodException.class, () -> EnvironmentCardDTO.class.getMethod("getUpdate"));
    assertTrue(
        List.of(EnvironmentCardDTO.class.getMethods()).stream()
            .noneMatch(method -> method.getName().equals("setUpdate")));
  }

  /** 意图：runtime wire 保留 revision/status，且无更新与未知总长都必须显式输出 null。 */
  @Test
  void runtimeStatusExplicitlySerializesNullableUpdateAndTotalBytes() {
    EnvironmentRuntimeDTO status = new EnvironmentRuntimeDTO();
    status.setDaemonVersion("1.0.9");
    status.setUpdate(null);
    EnvironmentRuntimeStatusDTO snapshot = new EnvironmentRuntimeStatusDTO();
    snapshot.setRevision("1");
    snapshot.setStatus(status);

    JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(snapshot));

    assertEquals("1", json.get("revision").stringValue());
    assertEquals("1.0.9", json.get("status").get("daemonVersion").stringValue());
    assertTrue(json.get("status").has("update"));
    assertTrue(json.get("status").get("update").isNull());

    EnvironmentUpdateProgressDTO progress = new EnvironmentUpdateProgressDTO();
    progress.setTargetVersion("1.0.10");
    progress.setDownloadedBytes(12L);
    progress.setTotalBytes(null);
    status.setUpdate(progress);
    JsonNode withProgress = MAPPER.readTree(MAPPER.writeValueAsString(snapshot));
    assertTrue(withProgress.get("status").get("update").has("totalBytes"));
    assertTrue(withProgress.get("status").get("update").get("totalBytes").isNull());
  }

  /** 意图：start update 响应必须显式区分命令是否已确认，不能只靠快照推断启动成功。 */
  @Test
  void updateStartResponseCarriesCommandStatus() throws Exception {
    assertEquals(
        List.of("runtimeStatus", "commandStatus"),
        List.of(EnvironmentUpdateStartDTO.class.getDeclaredFields()).stream()
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .map(Field::getName)
            .toList());
    assertEquals(
        List.of("CURRENT", "IN_PROGRESS", "SENT_UNCONFIRMED"),
        List.of(
                EnvironmentUpdateStartDTO.class.getField("CURRENT").get(null),
                EnvironmentUpdateStartDTO.class.getField("IN_PROGRESS").get(null),
                EnvironmentUpdateStartDTO.class.getField("SENT_UNCONFIRMED").get(null))
            .stream()
            .map(Object::toString)
            .toList());
  }

  /** 意图：验证 Card 不再携带历史事件；完整事件列表是有界的四项事实，只在 events 端点暴露。 */
  @Test
  void environmentCardCarriesNoEventAndEventsStayBoundedFacts() throws Exception {
    assertThrows(
        NoSuchFieldException.class, () -> EnvironmentCardDTO.class.getDeclaredField("lastEvent"));
    assertThrows(
        NoSuchMethodException.class, () -> EnvironmentCardDTO.class.getMethod("getLastEvent"));
    assertThrows(
        NoSuchMethodException.class,
        () -> EnvironmentCardDTO.class.getMethod("setLastEvent", EnvironmentEventDTO.class));
    assertEquals(
        List.of("time", "level", "type", "message"),
        List.of(EnvironmentEventDTO.class.getDeclaredFields()).stream()
            .map(Field::getName)
            .toList());

    // 事件时间在 wire 上是 Instant（Web 层按数值时间戳序列化）。
    assertEquals(Instant.class, EnvironmentEventDTO.class.getDeclaredField("time").getType());
  }

  /** 意图：验证 EnvironmentRotateTokenDTO 能正确反序列化 expectedVersion，且在携带未知字段时报错。 */
  @Test
  void environmentRotateTokenDtoAcceptsExpectedVersionAndRejectsUnknown() throws Exception {
    String json = "{\"expectedVersion\":\"5\"}";
    EnvironmentRotateTokenDTO dto = MAPPER.readValue(json, EnvironmentRotateTokenDTO.class);
    assertEquals("5", dto.getExpectedVersion());

    String badJson = "{\"expectedVersion\":\"5\",\"extra\":true}";
    assertThrows(Exception.class, () -> MAPPER.readValue(badJson, EnvironmentRotateTokenDTO.class));
  }
}
