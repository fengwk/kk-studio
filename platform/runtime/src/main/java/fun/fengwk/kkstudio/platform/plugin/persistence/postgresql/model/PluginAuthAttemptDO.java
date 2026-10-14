package fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** {@code plugin_auth_attempt} 单行的读取投影。 */
@Data
public class PluginAuthAttemptDO {

  private String pluginId;

  private String nonceHash;

  private UUID authGeneration;

  private String contextFingerprint;

  private Long configRevision;

  private String region;

  private Instant expiresAt;
}
