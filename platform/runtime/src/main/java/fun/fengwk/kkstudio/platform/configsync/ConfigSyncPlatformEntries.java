package fun.fengwk.kkstudio.platform.configsync;

import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncEntrySupport.RESOURCE;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncEntrySupport.reportUnknownConfig;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncEntrySupport.requireObject;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncEntrySupport.skipUnknown;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncEntrySupport.validateEntry;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpConfigParser;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogService;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncParser.EnvironmentSpec;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncParser.McpSpec;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncParser.SkillSpec;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncKind;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncSkipped;
import fun.fengwk.kkstudio.platform.environment.EnvironmentService;
import fun.fengwk.kkstudio.platform.environment.dto.DaemonConfigDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Skill Package / Environment / MCP Server 条目的读取与预校验。
 *
 * <p>三者的有效值都由写入路径的校验器判定（技能包用 {@link SkillCatalogService#validateImport}，Environment 用 {@link
 * EnvironmentService} 的静态校验，MCP 用 {@link McpConfigParser}），解析期只负责把字段读成强类型候选并保持 「先校验、再判定未知字段」的顺序。
 */
final class ConfigSyncPlatformEntries {

  private final ConfigSyncYaml yaml;
  private final SkillCatalogService skillCatalogService;

  ConfigSyncPlatformEntries(ConfigSyncYaml yaml, SkillCatalogService skillCatalogService) {
    this.yaml = yaml;
    this.skillCatalogService = skillCatalogService;
  }

  SkillSpec parseSkillPackage(
      ConfigSyncEntry entry, List<ConfigSyncSkipped> skipped, List<String> declared) {
    String packageName = entry.reqName("packageName");
    declared.add(packageName);
    String description = entry.optString("description");
    String repositoryUrl = entry.reqString("repositoryUrl");
    String branch = entry.reqString("branch");
    String token = entry.optString("token");
    // 唯一业务校验入口：validateImport 校验 canonical 包名、描述、URL、branch 与令牌。
    validateEntry(
        ConfigSyncKind.SKILL_PACKAGES,
        packageName,
        () ->
            skillCatalogService.validateImport(
                packageName, description, repositoryUrl, branch, token));
    if (skipUnknown(entry, ConfigSyncKind.SKILL_PACKAGES, packageName, skipped)) {
      return null;
    }
    return new SkillSpec(packageName, description, repositoryUrl, branch, token);
  }

  EnvironmentSpec parseEnvironment(
      ConfigSyncEntry entry, List<ConfigSyncSkipped> skipped, List<String> declared) {
    String name = entry.reqName("name");
    declared.add(name);
    if (entry.contains("installConfig") || entry.contains("operatingSystem")) {
      // 这两个字段不是可跳过的未知字段：必须在导入前置契约检查中明确拒绝，不能按未知字段静默跳过。
      throw DomainException.validation(
          RESOURCE,
          "environment entry uses removed installConfig/operatingSystem; use daemonConfig");
    }
    String tokenHash = entry.reqString("registrationTokenHash");
    String note = entry.optString("note");
    Object configNode = requireObject(entry, "daemonConfig");
    List<String> removed = new ArrayList<>();
    Object cleanedConfig =
        yaml.removeUnknownProperties(
            DaemonConfigDTO.class, configNode, entry.path() + ".daemonConfig", removed);
    DaemonConfigDTO config =
        yaml.convert(cleanedConfig, DaemonConfigDTO.class, entry.path() + ".daemonConfig");
    // 与 CRUD 共用静态校验并接住规范化结果：known 非法值必须硬拒绝，未知字段不能绕过。
    EnvironmentService.validateName(name);
    EnvironmentService.validateRegistrationTokenHash(tokenHash);
    note = EnvironmentService.validateNote(note);
    config = EnvironmentService.validateDaemonConfig(config);
    if (skipUnknown(entry, ConfigSyncKind.ENVIRONMENTS, name, skipped)) {
      return null;
    }
    if (reportUnknownConfig(removed, ConfigSyncKind.ENVIRONMENTS, name, skipped)) {
      return null;
    }
    return new EnvironmentSpec(name, note, tokenHash, config);
  }

  McpSpec parseMcpServer(
      ConfigSyncEntry entry, List<ConfigSyncSkipped> skipped, List<String> declared) {
    String name = entry.reqName("name");
    declared.add(name);
    String url = entry.reqString("url");
    Map<String, String> headers = entry.optStringMap("headers");
    Boolean enabled = entry.optBoolean("enabled");
    Long timeoutMillis = entry.optLong("timeoutMillis");
    // 与 CRUD 共用校验并规范化；未知字段不能绕过 name/url/header/timeout 的硬错误。
    validateEntry(ConfigSyncKind.MCP_SERVERS, name, () -> McpConfigParser.requireName(name));
    McpConfigParser.HttpConfig config =
        normalizeMcpConfig(name, url, headers, enabled, timeoutMillis);
    if (skipUnknown(entry, ConfigSyncKind.MCP_SERVERS, name, skipped)) {
      return null;
    }
    return new McpSpec(name, config);
  }

  private static McpConfigParser.HttpConfig normalizeMcpConfig(
      String name, String url, Map<String, String> headers, Boolean enabled, Long timeoutMillis) {
    try {
      return McpConfigParser.normalizeHttpConfig(url, headers, enabled, timeoutMillis);
    } catch (DomainException error) {
      if (error.code() != DomainErrorCode.VALIDATION) {
        throw error;
      }
      // 底层消息可能含 URL 或 header 值，统一替换为安全的类别与条目名，不携带 cause。
      throw DomainException.validation(
          RESOURCE, "mcp server " + name + " has an invalid url, headers or timeout");
    }
  }
}
