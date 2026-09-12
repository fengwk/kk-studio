package fun.fengwk.kkstudio.platform.environment.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceType;
import fun.fengwk.kkstudio.platform.environment.skill.SkillSourceConfigValidator.NormalizedConfig;
import fun.fengwk.kkstudio.platform.environment.skill.model.EnvironmentInventory;
import fun.fengwk.kkstudio.platform.environment.skill.model.SkillSource;
import fun.fengwk.kkstudio.platform.environment.skill.repo.impl.model.EnvironmentInventoryDO;
import fun.fengwk.kkstudio.platform.environment.skill.repo.impl.model.EnvironmentSkillSourceDO;
import fun.fengwk.kkstudio.platform.error.AiValidationException;

import java.util.UUID;

/** Skill 来源配置的纯词法校验契约：绝不解析远端路径，也绝不回显可能携带凭据的字段原值。 */
class SkillSourceConfigValidatorTest {

  /** 意图：PATH 接受 {@code ~/} 前导形式与三类目标 OS 词法绝对路径，拒绝相对路径与裸 {@code ~}。 */
  @Test
  void pathAcceptsTildePrefixAndAllSupportedAbsoluteForms() {
    assertEquals(
        "~/.agents/skills",
        SkillSourceConfigValidator.normalize("path", "~/.agents/skills", null, null, null).path());
    assertEquals(
        "/srv/skills",
        SkillSourceConfigValidator.normalize("path", "/srv/skills", null, null, null).path());
    assertEquals(
        "C:\\skills",
        SkillSourceConfigValidator.normalize("path", "C:\\skills", null, null, null).path());
    assertEquals(
        "d:/skills",
        SkillSourceConfigValidator.normalize("path", "d:/skills", null, null, null).path());
    assertEquals(
        "\\\\server\\share\\skills",
        SkillSourceConfigValidator.normalize("path", "\\\\server\\share\\skills", null, null, null)
            .path());

    assertThrows(
        AiValidationException.class,
        () -> SkillSourceConfigValidator.normalize("path", "relative/skills", null, null, null));
    // 只有前导 '~/' 被接受；裸 '~' 与 '~user' 都不是可展开形式。
    assertThrows(
        AiValidationException.class,
        () -> SkillSourceConfigValidator.normalize("path", "~", null, null, null));
    assertThrows(
        AiValidationException.class,
        () -> SkillSourceConfigValidator.normalize("path", "~user/skills", null, null, null));
    // 盘符相对路径不是绝对路径。
    assertThrows(
        AiValidationException.class,
        () -> SkillSourceConfigValidator.normalize("path", "C:skills", null, null, null));
  }

  /** 意图：词法校验不做任何展开，带未展开占位符或环绕空白的文本按原样接受/拒绝，而不是猜测用户意图。 */
  @Test
  void pathKeepsTextVerbatimAndRejectsSurroundingWhitespace() {
    assertEquals(
        "/srv/$VAR/skills",
        SkillSourceConfigValidator.normalize("path", "/srv/$VAR/skills", null, null, null).path());
    assertThrows(
        AiValidationException.class,
        () -> SkillSourceConfigValidator.normalize("path", " /srv/skills", null, null, null));
    assertThrows(
        AiValidationException.class,
        () -> SkillSourceConfigValidator.normalize("path", " ", null, null, null));
    assertThrows(
        AiValidationException.class,
        () ->
            SkillSourceConfigValidator.normalize(
                "path", "/srv/" + "a".repeat(4096), null, null, null));
  }

  /** 意图：类型专属字段必须互斥；PATH 不接受任何 GIT 字段，GIT 不接受 path。 */
  @Test
  void typeSpecificFieldsAreMutuallyExclusive() {
    assertThrows(
        AiValidationException.class,
        () ->
            SkillSourceConfigValidator.normalize(
                "path", "/srv/skills", "https://example.test/repo.git", null, null));
    assertThrows(
        AiValidationException.class,
        () ->
            SkillSourceConfigValidator.normalize(
                "git", "/srv/skills", "https://example.test/repo.git", null, null));
    assertThrows(
        AiValidationException.class,
        () -> SkillSourceConfigValidator.normalize("git", null, null, "main", null));
    assertThrows(
        AiValidationException.class,
        () -> SkillSourceConfigValidator.normalize("other", "/srv/skills", null, null, null));
    assertThrows(
        AiValidationException.class,
        () -> SkillSourceConfigValidator.normalize(null, "/srv/skills", null, null, null));
  }

  /** 意图：GIT 的 ref/scanPath 约束与 DaemonSkillSourceConfig 一致，因此 Platform 接受的配置一定能被 Daemon 解码。 */
  @Test
  void gitReusesDaemonConstraintsExactly() {
    NormalizedConfig config =
        SkillSourceConfigValidator.normalize(
            "git", null, "https://example.test/repo.git", "main", "skills\\nested");
    assertEquals(DaemonSkillSourceType.GIT, config.type());
    assertNull(config.path());
    assertEquals("main", config.gitRef());
    // 与 Daemon 相同的归一化：反斜杠统一为 '/'。
    assertEquals("skills/nested", config.scanPath());

    // 空文本与 '.' 都归一为仓库根（null）。
    assertNull(
        SkillSourceConfigValidator.normalize(
                "git", null, "https://example.test/repo.git", null, ".")
            .scanPath());
    assertNull(
        SkillSourceConfigValidator.normalize(
                "git", null, "https://example.test/repo.git", null, "  ")
            .scanPath());

    assertThrows(
        AiValidationException.class,
        () ->
            SkillSourceConfigValidator.normalize(
                "git", null, "https://example.test/repo.git", "-branch", null));
    assertThrows(
        AiValidationException.class,
        () ->
            SkillSourceConfigValidator.normalize(
                "git", null, "https://example.test/repo.git", null, "skills/../outside"));
    assertThrows(
        AiValidationException.class,
        () ->
            SkillSourceConfigValidator.normalize(
                "git", null, "https://example.test/repo.git", null, "/absolute"));
    assertThrows(
        AiValidationException.class,
        () ->
            SkillSourceConfigValidator.normalize(
                "git", null, "https://example.test/repo.git", null, "C:/absolute"));
    assertThrows(
        AiValidationException.class,
        () ->
            SkillSourceConfigValidator.normalize(
                "git", null, "https://example.test/repo.git", "a".repeat(1025), null));
  }

  /** 意图：错误消息只报告结构性问题，绝不回显 url/ref/path 原值（这些字段可能携带用户凭据）。 */
  @Test
  void errorMessagesNeverEchoConfigurationValues() {
    String secretUrl = "https://user:topsecret@example.test/repo.git";
    AiValidationException error =
        assertThrows(
            AiValidationException.class,
            () -> SkillSourceConfigValidator.normalize("git", null, secretUrl, "-ref", null));
    assertTrue(error.getMessage().contains("gitRef"), error.getMessage());
    assertTrue(!error.getMessage().contains("topsecret"), error.getMessage());
    assertTrue(!error.getMessage().contains(secretUrl), error.getMessage());

    AiValidationException pathError =
        assertThrows(
            AiValidationException.class,
            () ->
                SkillSourceConfigValidator.normalize(
                    "path", "relative/secret-skills", null, null, null));
    assertTrue(!pathError.getMessage().contains("secret-skills"), pathError.getMessage());
  }

  /**
   * 意图：SkillSource 与 EnvironmentSkillSourceDO 的 toString() 排除全部具体配置值（path/gitUrl/gitRef/scanPath）。
   */
  @Test
  void domainModelsAndDosExcludeConfigValuesFromToString() {
    SkillSource model = new SkillSource();
    model.setSourceId(UUID.randomUUID());
    model.setEnvironmentId(UUID.randomUUID());
    model.setType(DaemonSkillSourceType.GIT);
    model.setPath("MODEL_SECRET_PATH");
    model.setGitUrl("https://user:MODEL_SECRET_URL@example.test/repo.git");
    model.setGitRef("MODEL_SECRET_REF");
    model.setScanPath("MODEL_SECRET_SCAN");

    String modelStr = model.toString();
    assertFalse(modelStr.contains("MODEL_SECRET_PATH"), modelStr);
    assertFalse(modelStr.contains("MODEL_SECRET_URL"), modelStr);
    assertFalse(modelStr.contains("MODEL_SECRET_REF"), modelStr);
    assertFalse(modelStr.contains("MODEL_SECRET_SCAN"), modelStr);

    EnvironmentSkillSourceDO dataObject = new EnvironmentSkillSourceDO();
    dataObject.setSourceId(UUID.randomUUID());
    dataObject.setEnvironmentId(UUID.randomUUID());
    dataObject.setSourceType("git");
    dataObject.setPath("DO_SECRET_PATH");
    dataObject.setGitUrl("https://user:DO_SECRET_URL@example.test/repo.git");
    dataObject.setGitRef("DO_SECRET_REF");
    dataObject.setScanPath("DO_SECRET_SCAN");

    String doStr = dataObject.toString();
    assertFalse(doStr.contains("DO_SECRET_PATH"), doStr);
    assertFalse(doStr.contains("DO_SECRET_URL"), doStr);
    assertFalse(doStr.contains("DO_SECRET_REF"), doStr);
    assertFalse(doStr.contains("DO_SECRET_SCAN"), doStr);
  }

  /** 意图：EnvironmentInventory 与 EnvironmentInventoryDO 的 toString() 排除 leaseToken 敏感代币。 */
  @Test
  void inventoryModelsAndDosExcludeLeaseTokenFromToString() {
    UUID leaseToken = UUID.randomUUID();

    EnvironmentInventory model = new EnvironmentInventory();
    model.setEnvironmentId(UUID.randomUUID());
    model.setLeaseToken(leaseToken);
    assertFalse(model.toString().contains(leaseToken.toString()));

    EnvironmentInventoryDO dataObject = new EnvironmentInventoryDO();
    dataObject.setEnvironmentId(UUID.randomUUID());
    dataObject.setLeaseToken(leaseToken);
    assertFalse(dataObject.toString().contains(leaseToken.toString()));
  }

  /** 意图：SkillDiagnosticsCodec 解码未知字段或畸变 JSON 时抛出固定结构化异常，不回显未知键名与值，且不保留异常原因。 */
  @Test
  void diagnosticsCodecThrowsConstantMessagesWithoutCauseOrLeakedValues() {
    SkillDiagnosticsCodec codec = new SkillDiagnosticsCodec();

    IllegalArgumentException unknownFieldEx =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                codec.decode(
                    "[{\"location\":\"/loc\",\"message\":\"msg\",\"SECRET_UNKNOWN_FIELD\":\"SECRET_VAL\"}]"));
    assertEquals("unexpected skill diagnostic field", unknownFieldEx.getMessage());
    assertNull(unknownFieldEx.getCause());

    IllegalArgumentException malformedEx =
        assertThrows(IllegalArgumentException.class, () -> codec.decode("not-a-json"));
    assertEquals("malformed skill diagnostics", malformedEx.getMessage());
    assertNull(malformedEx.getCause());
  }
}
