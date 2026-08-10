package fun.fengwk.kkstudio.core.studio.function.h3;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

import java.time.Duration;

/** MiniMax-H3 Ref2VA adapter 的显式运行配置。 */
@ConfigurationProperties(prefix = "kk-studio.canvas.function.minimax-h3")
@Data
public class MiniMaxH3Properties {

  static final int MAX_AGENT_NAME_LENGTH = 64;
  static final long MAX_PRESIGN_EXPIRY_SECONDS = 3600L;
  static final Duration MAX_PROMPT_WAIT = Duration.ofMinutes(30);
  static final Duration MAX_COMFY_CONNECT_TIMEOUT = Duration.ofMinutes(1);
  static final Duration MAX_COMFY_REQUEST_TIMEOUT = Duration.ofMinutes(5);
  static final Duration MAX_COMFY_POLL_INTERVAL = Duration.ofMinutes(1);
  static final Duration MAX_COMFY_WAIT = Duration.ofHours(2);

  private boolean enabled;
  private String promptAgentName;
  private String promptEnvironmentName;
  private long presignExpirySeconds = 600L;
  private Duration promptMaxWait = Duration.ofMinutes(10);
  private String comfyBaseUrl;
  private String comfyBearerToken;
  private Duration comfyConnectTimeout = Duration.ofSeconds(10);
  private Duration comfyRequestTimeout = Duration.ofSeconds(30);
  private Duration comfyPollInterval = Duration.ofSeconds(2);
  private Duration comfyMaxWait = Duration.ofMinutes(30);

  /** 启用 H3 时统一验证 durable identity 与所有运行时等待边界。 */
  public void validateEnabled() {
    validateAgentName(promptAgentName);
    validateEnvironmentName(promptEnvironmentName);
    if (presignExpirySeconds < 1L || presignExpirySeconds > MAX_PRESIGN_EXPIRY_SECONDS) {
      throw new IllegalArgumentException(
          "presignExpirySeconds must be between 1 and " + MAX_PRESIGN_EXPIRY_SECONDS);
    }
    requirePositiveBounded(promptMaxWait, MAX_PROMPT_WAIT, "promptMaxWait");
    requirePositiveBounded(comfyConnectTimeout, MAX_COMFY_CONNECT_TIMEOUT, "comfyConnectTimeout");
    requirePositiveBounded(comfyRequestTimeout, MAX_COMFY_REQUEST_TIMEOUT, "comfyRequestTimeout");
    requirePositiveBounded(comfyPollInterval, MAX_COMFY_POLL_INTERVAL, "comfyPollInterval");
    requirePositiveBounded(comfyMaxWait, MAX_COMFY_WAIT, "comfyMaxWait");
    if (comfyPollInterval.compareTo(comfyMaxWait) >= 0) {
      throw new IllegalArgumentException("comfyPollInterval must be less than comfyMaxWait");
    }
  }

  private static void validateAgentName(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("promptAgentName must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException("promptAgentName must not contain surrounding whitespace");
    }
    if (value.indexOf('/') >= 0) {
      throw new IllegalArgumentException("promptAgentName must not contain '/'");
    }
    if (value.length() > MAX_AGENT_NAME_LENGTH) {
      throw new IllegalArgumentException(
          "promptAgentName must be at most " + MAX_AGENT_NAME_LENGTH + " characters");
    }
  }

  private static void validateEnvironmentName(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("promptEnvironmentName must not be blank");
    }
    new EnvironmentName(value);
  }

  private static void requirePositiveBounded(Duration value, Duration maximum, String field) {
    if (value == null || value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
      throw new IllegalArgumentException(field + " must be positive and at most " + maximum);
    }
  }
}
