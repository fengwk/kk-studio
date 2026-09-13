package fun.fengwk.kkstudio.share.ai.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

/** 验证 Skill 来源与持久 inventory DTO 的序列化、严格未知字段校验与凭据不外泄契约。 */
class EnvironmentSkillSourceDtoContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 意图：PATH 创建请求只接受 type/path，任何未知字段立即失败。 */
  @Test
  void createDtoAcceptsPathAndRejectsUnknownFields() throws Exception {
    EnvironmentSkillSourceCreateDTO dto =
        MAPPER.readValue(
            "{\"type\":\"path\",\"path\":\"~/.agents/skills\"}",
            EnvironmentSkillSourceCreateDTO.class);
    assertEquals("path", dto.getType());
    assertEquals("~/.agents/skills", dto.getPath());
    assertNull(dto.getGitUrl());

    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                "{\"type\":\"path\",\"path\":\"/srv/skills\",\"defaultSource\":true}",
                EnvironmentSkillSourceCreateDTO.class),
        "defaultSource 是服务端拥有的字段，客户端提交必须失败");
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                "{\"type\":\"git\",\"gitUrl\":\"https://example.test/repo.git\",\"unknown\":1}",
                EnvironmentSkillSourceCreateDTO.class));
  }

  /** 意图：更新请求必须携带 expectedVersion，并且拒绝未知字段。 */
  @Test
  void updateDtoCarriesExpectedVersionAndRejectsUnknownFields() throws Exception {
    EnvironmentSkillSourceUpdateDTO dto =
        MAPPER.readValue(
            "{\"type\":\"git\",\"gitUrl\":\"https://example.test/repo.git\",\"gitRef\":\"main\","
                + "\"scanPath\":\"skills\",\"expectedVersion\":\"3\"}",
            EnvironmentSkillSourceUpdateDTO.class);
    assertEquals("git", dto.getType());
    assertEquals("main", dto.getGitRef());
    assertEquals("skills", dto.getScanPath());
    assertEquals("3", dto.getExpectedVersion());

    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                "{\"type\":\"path\",\"path\":\"/srv/skills\",\"expectedVersion\":\"0\",\"version\":\"0\"}",
                EnvironmentSkillSourceUpdateDTO.class));
  }

  /**
   * 意图：来源响应必须回显全部可编辑字段（含 gitUrl，供编辑表单填充），但 {@code toString()}
   * 不得包含配置值（path/gitUrl/gitRef/scanPath），避免敏感信息被日志顺带写出。
   */
  @Test
  void sourceDtoSerializesGitUrlButExcludesConfigValuesFromToString() throws Exception {
    EnvironmentSkillSourceDTO dto = new EnvironmentSkillSourceDTO();
    dto.setSourceId("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    dto.setEnvironmentId("11111111-1111-1111-1111-111111111111");
    dto.setType("git");
    dto.setPath("PATH_SECRET_ROOT");
    dto.setGitUrl("https://user:GIT_URL_SECRET@example.test/repo.git");
    dto.setGitRef("GIT_REF_SECRET");
    dto.setScanPath("SCAN_PATH_SECRET");
    dto.setDefaultSource(false);
    dto.setVersion("2");
    dto.setStatus("UNAPPLIED");
    dto.setAppliedVersion("1");
    dto.setAppliedRevision("0123456789abcdef0123456789abcdef01234567");
    dto.setDiagnostics(List.of(new EnvironmentSkillDiagnosticDTO()));

    JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(dto));
    assertEquals("https://user:GIT_URL_SECRET@example.test/repo.git", json.get("gitUrl").asText());
    assertEquals("2", json.get("version").asText());

    String text = dto.toString();
    assertFalse(text.contains("PATH_SECRET_ROOT"), "toString 不得输出 path：" + text);
    assertFalse(text.contains("GIT_URL_SECRET"), "toString 不得输出 gitUrl：" + text);
    assertFalse(text.contains("GIT_REF_SECRET"), "toString 不得输出 gitRef：" + text);
    assertFalse(text.contains("SCAN_PATH_SECRET"), "toString 不得输出 scanPath：" + text);
    assertTrue(text.contains("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
  }

  /** 意图：创建与更新请求 DTO 的 toString() 均排除所有具体来源配置值（path/gitUrl/gitRef/scanPath）。 */
  @Test
  void createAndUpdateDtosExcludeAllConfigValuesFromToString() {
    EnvironmentSkillSourceCreateDTO create = new EnvironmentSkillSourceCreateDTO();
    create.setType("git");
    create.setPath("SECRET_CREATE_PATH");
    create.setGitUrl("SECRET_CREATE_GIT_URL");
    create.setGitRef("SECRET_CREATE_GIT_REF");
    create.setScanPath("SECRET_CREATE_SCAN_PATH");

    String createStr = create.toString();
    assertFalse(createStr.contains("SECRET_CREATE_PATH"));
    assertFalse(createStr.contains("SECRET_CREATE_GIT_URL"));
    assertFalse(createStr.contains("SECRET_CREATE_GIT_REF"));
    assertFalse(createStr.contains("SECRET_CREATE_SCAN_PATH"));

    EnvironmentSkillSourceUpdateDTO update = new EnvironmentSkillSourceUpdateDTO();
    update.setType("path");
    update.setPath("SECRET_UPDATE_PATH");
    update.setGitUrl("SECRET_UPDATE_GIT_URL");
    update.setGitRef("SECRET_UPDATE_GIT_REF");
    update.setScanPath("SECRET_UPDATE_SCAN_PATH");
    update.setExpectedVersion("1");

    String updateStr = update.toString();
    assertFalse(updateStr.contains("SECRET_UPDATE_PATH"));
    assertFalse(updateStr.contains("SECRET_UPDATE_GIT_URL"));
    assertFalse(updateStr.contains("SECRET_UPDATE_GIT_REF"));
    assertFalse(updateStr.contains("SECRET_UPDATE_SCAN_PATH"));
    assertTrue(updateStr.contains("expectedVersion=1"));
  }

  /** 意图：ownerNodeId 与 leaseToken 是内部围栏事实，不属于公共 API，EnvironmentInventoryDTO 拒绝它们。 */
  @Test
  void inventoryDtoRejectsInternalFenceFields() {
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                "{\"environmentId\":\"11111111-1111-1111-1111-111111111111\","
                    + "\"sourceSetVersion\":\"0\",\"ownerNodeId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\"}",
                EnvironmentInventoryDTO.class));

    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                "{\"environmentId\":\"11111111-1111-1111-1111-111111111111\","
                    + "\"sourceSetVersion\":\"0\",\"leaseToken\":\"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb\"}",
                EnvironmentInventoryDTO.class));
  }

  /**
   * 意图：持久 inventory 与 Skill 响应携带身份/版本/revision 等冻结字段，且拒绝未知字段。
   *
   * <p>测试不覆盖 {@code Instant} 字段的反序列化：share 模块没有 jsr310 依赖（时间字段与 {@code EnvironmentCardDTO} 使用同一
   * 约定，由 web 层的 Jackson 模块处理）。
   */
  @Test
  void inventoryAndSkillDtosExposeFrozenFieldsAndRejectUnknownFields() throws Exception {
    EnvironmentSkillDTO skill =
        MAPPER.readValue(
            "{\"sourceId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\",\"name\":\"dev\","
                + "\"sourceVersion\":\"2\",\"description\":\"dev skill\","
                + "\"baseDirectory\":\"/home/dev/skills/dev\",\"contentRevision\":\""
                + "0".repeat(64)
                + "\"}",
            EnvironmentSkillDTO.class);
    assertEquals("dev", skill.getName());
    assertEquals("2", skill.getSourceVersion());
    assertEquals("0".repeat(64), skill.getContentRevision());

    EnvironmentInventoryDTO inventory =
        MAPPER.readValue(
            "{\"environmentId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"sourceSetVersion\":\"2\",\"appliedSourceSetVersion\":\"1\","
                + "\"capabilitiesVersion\":2,\"operatingSystem\":\"linux\",\"timeZone\":\"UTC\","
                + "\"note\":\"Linux environment.\",\"rootPath\":\"/home/dev\"}",
            EnvironmentInventoryDTO.class);
    assertEquals(2, inventory.getCapabilitiesVersion());
    assertEquals("linux", inventory.getOperatingSystem());
    assertEquals("2", inventory.getSourceSetVersion());

    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                "{\"sourceId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\",\"name\":\"dev\","
                    + "\"sourceVersion\":\"2\",\"description\":\"d\",\"baseDirectory\":\"/d\","
                    + "\"contentRevision\":\""
                    + "0".repeat(64)
                    + "\",\"body\":\"secret text\"}",
                EnvironmentSkillDTO.class),
        "Skill 正文绝不进入 DTO，未知字段必须失败");
  }
}
