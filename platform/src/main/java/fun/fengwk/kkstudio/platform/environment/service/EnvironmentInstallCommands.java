package fun.fengwk.kkstudio.platform.environment.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.environment.DaemonConfiguration;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallConfigDTO;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** 把已保存的安装设置渲染成用户只需复制的一行命令。复杂的私有暂存、下载与清理留在脚本里，不新增票据、签名或安装器副本。 */
public final class EnvironmentInstallCommands {
  static final String INSTALLER_BASE =
      "https://raw.githubusercontent.com/fengwk/kk-studio/main/scripts/daemon";

  private static final ObjectMapper JSON =
      new ObjectMapper().setDefaultPropertyInclusion(JsonInclude.Include.ALWAYS);
  private static final String UNIX = load("environment-install-unix.sh");
  private static final String WINDOWS = load("environment-install-windows.ps1");

  private EnvironmentInstallCommands() {}

  public static String install(EnvironmentInstallConfigDTO config, String registrationToken) {
    EnvironmentInstallConfigDTO normalized = EnvironmentInstallConfigs.validate(config);
    text(registrationToken, "registrationToken");
    boolean windows = "windows".equals(normalized.getOperatingSystem());
    return render(
        windows,
        "install",
        daemonJson(normalized.getDaemon()),
        registrationToken,
        normalized.getJavaHome());
  }

  public static String uninstall(String operatingSystem) {
    if (!Set.of("linux", "macos", "windows").contains(operatingSystem)) {
      throw new AiValidationException(
          "operatingSystem", "operatingSystem must be linux, macos or windows");
    }
    return render("windows".equals(operatingSystem), "uninstall", "", "", null);
  }

  private static String render(
      boolean windows, String action, String daemonJson, String token, String javaHome) {
    String template = windows ? WINDOWS : UNIX;
    String parameters = "";
    if (!daemonJson.isEmpty()) {
      parameters =
          windows
              ? " -ConfigFile $config -TokenFile $token"
              : " --config-file \"$stage/daemon.json\" --token-file \"$stage/daemon.token\"";
      if (javaHome != null) {
        parameters += windows ? " -JavaHome " + ps(javaHome) : " --java-home " + sh(javaHome);
      }
    }
    String staging =
        daemonJson.isEmpty()
            ? ""
            : (windows ? windowsStaging(daemonJson, token) : unixStaging(daemonJson, token));
    return template
        .replace(staging.isEmpty() ? "@@STAGING@@\n" : "@@STAGING@@", staging)
        .replace(
            "@@INSTALLER@@",
            windows ? ps(INSTALLER_BASE + "/install.ps1") : sh(INSTALLER_BASE + "/install.sh"))
        .replace("@@ACTION@@", action)
        .replace("@@PARAMETERS@@", parameters);
  }

  private static String unixStaging(String daemonJson, String token) {
    return """
        builtin printf '%%s' %s > "$stage/daemon.json"
        builtin printf '%%s' %s > "$stage/daemon.token"
        chmod 600 "$stage/daemon.json" "$stage/daemon.token"
        """
        .formatted(sh(daemonJson), sh(token));
  }

  private static String windowsStaging(String daemonJson, String token) {
    return """
          $config = Join-Path $stage 'daemon.json'
          $token = Join-Path $stage 'daemon.token'
          foreach ($path in @($config, $token)) {
            $stream = [IO.File]::Open($path, [IO.FileMode]::CreateNew)
            $stream.Dispose()
            Set-KkPrivateAcl -Path $path
          }
          $utf8 = New-Object Text.UTF8Encoding($false)
          [IO.File]::WriteAllText($config, %%s, $utf8)
          [IO.File]::WriteAllText($token, %%s, $utf8)
        """
        .formatted(ps(daemonJson), ps(token));
  }

  private static String daemonJson(DaemonConfiguration daemon) {
    try {
      return JSON.writeValueAsString(daemon);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot serialize daemon configuration", error);
    }
  }

  /** POSIX 单引号：内部单引号结束当前字面量、插入转义单引号后再继续。 */
  static String sh(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  /** PowerShell 单引号字符串：内部单引号写成两个单引号。 */
  static String ps(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  private static void text(String value, String field) {
    if (value == null
        || value.isBlank()
        || value.codePoints().anyMatch(Character::isISOControl)
        || value.indexOf('\u2028') >= 0
        || value.indexOf('\u2029') >= 0) {
      throw new AiValidationException(field, field + " must be text without control characters");
    }
  }

  private static String load(String name) {
    try (InputStream input =
        EnvironmentInstallCommands.class.getResourceAsStream(
            "/fun/fengwk/kkstudio/platform/environment/service/" + name)) {
      if (input == null) {
        throw new IllegalStateException("missing install command template " + name);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip() + "\n";
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
