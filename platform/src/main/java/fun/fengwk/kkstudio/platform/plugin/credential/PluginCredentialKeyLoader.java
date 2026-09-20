package fun.fengwk.kkstudio.platform.plugin.credential;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.platform.plugin.PluginKeyUnavailableException;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 部署级 Plugin 凭据主密钥加载器。
 *
 * <p>主密钥是 owner-only 的绝对路径文件，内容恰好 32 bytes 原始 AES-256 密钥；它不进数据库、SystemSettings、DTO 或日志。读取成功后缓存在本对象内
 * （key file 是进程启动边界）；读取失败时每次重新探测，但只记录一次原因，使 key file 缺失的部署仍能启动，同时读写 fail closed。
 *
 * <p>日志只输出路径与失败原因，绝不输出密钥字节或长度以外的内容。
 */
@Slf4j
public final class PluginCredentialKeyLoader {

  /** AES-256 主密钥的精确字节数。 */
  public static final int KEY_BYTES = 32;

  private final String configuredPath;

  private final AtomicBoolean failureLogged = new AtomicBoolean(false);

  private volatile SecretKey cachedKey;

  public PluginCredentialKeyLoader(String configuredPath) {
    this.configuredPath = configuredPath;
  }

  /** 读取主密钥；任何形态问题（未配置、非绝对路径、非普通文件、权限过宽、长度不是 32 bytes）都返回空，由调用方 fail closed。 */
  public Optional<SecretKey> load() {
    SecretKey current = cachedKey;
    if (current != null) {
      return Optional.of(current);
    }
    Optional<SecretKey> loaded = probe();
    loaded.ifPresent(key -> cachedKey = key);
    if (loaded.isEmpty() && failureLogged.compareAndSet(false, true)) {
      log.warn(
          "Plugin credential key is unavailable at {}; plugin authentication fails closed",
          descriptor());
    }
    return loaded;
  }

  /**
   * 加载主密钥，不可用时抛出 {@link PluginKeyUnavailableException}。
   *
   * <p>用于写路径与错误消息需要明确原因的调用点。
   */
  public SecretKey require() {
    return load()
        .orElseThrow(
            () ->
                new PluginKeyUnavailableException(
                    "plugin credential key is unavailable at " + descriptor()));
  }

  private Optional<SecretKey> probe() {
    if (configuredPath == null || configuredPath.isBlank()) {
      return Optional.empty();
    }
    Path path;
    try {
      path = Path.of(configuredPath.strip());
    } catch (RuntimeException error) {
      return Optional.empty();
    }
    if (!path.isAbsolute()) {
      log.warn("Plugin credential key file must be an absolute path");
      return Optional.empty();
    }
    try {
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        return Optional.empty();
      }
      if (!isOwnerOnly(path)) {
        log.warn("Plugin credential key file must not be readable by group or others");
        return Optional.empty();
      }
      byte[] raw = Files.readAllBytes(path);
      if (raw.length != KEY_BYTES) {
        log.warn(
            "Plugin credential key file must contain exactly {} bytes of raw AES-256 key material",
            KEY_BYTES);
        return Optional.empty();
      }
      return Optional.of(new SecretKeySpec(raw, "AES"));
    } catch (IOException | UnsupportedOperationException error) {
      log.warn("Cannot read plugin credential key file: {}", error.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  private static boolean isOwnerOnly(Path path) throws IOException {
    Set<PosixFilePermission> permissions =
        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
    for (PosixFilePermission permission : permissions) {
      if (permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_")) {
        return false;
      }
    }
    return permissions.contains(PosixFilePermission.OWNER_READ);
  }

  private String descriptor() {
    return configuredPath == null || configuredPath.isBlank() ? "<not configured>" : configuredPath;
  }
}
