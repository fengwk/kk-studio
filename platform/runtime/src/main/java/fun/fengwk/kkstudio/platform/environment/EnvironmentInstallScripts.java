package fun.fengwk.kkstudio.platform.environment;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.environment.dto.DaemonConfigCodec;
import fun.fengwk.kkstudio.platform.environment.dto.DaemonConfigDTO;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 把一张卡片的完整配置渲染成只下载固定官方 tag 安装器的安装或卸载脚本。 */
public final class EnvironmentInstallScripts {
  static final String INSTALLER_BASE =
      "https://raw.githubusercontent.com/fengwk/kk-studio/v%s/scripts/daemon";

  private static final Pattern RELEASE_VERSION = Pattern.compile("[0-9A-Za-z._+-]{1,64}");
  private static final Set<DaemonOperatingSystem> INSTALLABLE =
      Set.of(
          DaemonOperatingSystem.LINUX, DaemonOperatingSystem.MACOS, DaemonOperatingSystem.WINDOWS);
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .changeDefaultPropertyInclusion(
              inclusion -> inclusion.withValueInclusion(JsonInclude.Include.ALWAYS))
          .build();
  private static final String UNIX = load("environment-install-unix.sh");
  private static final String WINDOWS = load("environment-install-windows.ps1");

  private EnvironmentInstallScripts() {}

  public static String install(
      UUID environmentId,
      long configurationVersion,
      DaemonConfigDTO daemonConfig,
      String registrationToken,
      DaemonOperatingSystem operatingSystem,
      String releaseVersion) {
    Objects.requireNonNull(environmentId, "environmentId");
    if (configurationVersion < 0) {
      throw new IllegalArgumentException("configurationVersion must be non-negative");
    }
    String version = releaseVersion(releaseVersion);
    requireInstallableOperatingSystem(operatingSystem);
    text(registrationToken, "registrationToken");
    String daemonJson = daemonJson(environmentId, configurationVersion, daemonConfig);
    boolean windows = operatingSystem == DaemonOperatingSystem.WINDOWS;
    String action =
        windows
            ? "-Command install -EnvironmentId "
                + ps(environmentId.toString())
                + " -Version "
                + ps(version)
                + " -ConfigFile $config -TokenFile $token"
            : "install --environment-id "
                + sh(environmentId.toString())
                + " --version "
                + sh(version)
                + " --config-file \"$stage/daemon.json\" --token-file \"$stage/daemon.token\"";
    return render(windows, version, daemonJson, registrationToken, action);
  }

  public static String uninstall(
      UUID environmentId, DaemonOperatingSystem operatingSystem, String releaseVersion) {
    Objects.requireNonNull(environmentId, "environmentId");
    String version = releaseVersion(releaseVersion);
    requireInstallableOperatingSystem(operatingSystem);
    boolean windows = operatingSystem == DaemonOperatingSystem.WINDOWS;
    String action =
        windows
            ? "-Command uninstall -EnvironmentId " + ps(environmentId.toString())
            : "uninstall --environment-id " + sh(environmentId.toString());
    return render(windows, version, "", "", action);
  }

  static void requireInstallableOperatingSystem(DaemonOperatingSystem operatingSystem) {
    if (operatingSystem == null || !INSTALLABLE.contains(operatingSystem)) {
      throw DomainException.validation(
          "operatingSystem", "operatingSystem must be linux, macos or windows");
    }
  }

  private static String releaseVersion(String value) {
    if (value == null || !RELEASE_VERSION.matcher(value).matches()) {
      throw new IllegalStateException("daemon release version is unavailable");
    }
    return value;
  }

  private static String render(
      boolean windows, String releaseVersion, String daemonJson, String token, String action) {
    String template = windows ? WINDOWS : UNIX;
    String installerBase = INSTALLER_BASE.formatted(releaseVersion);
    if (windows) {
      String staging = daemonJson.isEmpty() ? "" : windowsStaging(daemonJson, token);
      return template.formatted(staging, ps(installerBase + "/install.ps1"), action);
    }
    String staging = daemonJson.isEmpty() ? "" : unixStaging(daemonJson, token);
    return template.formatted(staging, sh(installerBase + "/install.sh"), action);
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
          [IO.File]::WriteAllText($config, %s, $utf8)
          [IO.File]::WriteAllText($token, %s, $utf8)
        """
        .formatted(ps(daemonJson), ps(token));
  }

  private static String daemonJson(
      UUID environmentId, long configurationVersion, DaemonConfigDTO daemonConfig) {
    ObjectNode envelope = JSON.createObjectNode();
    envelope.put("environmentId", environmentId.toString());
    envelope.put("version", Long.toString(configurationVersion));
    envelope.set("daemonConfig", DaemonConfigCodec.tree(daemonConfig));
    try {
      return JSON.writeValueAsString(envelope);
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot serialize daemon configuration envelope", error);
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
      throw DomainException.validation(field, field + " must be text without control characters");
    }
  }

  private static String load(String name) {
    try (InputStream input =
        EnvironmentInstallScripts.class.getResourceAsStream(
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
