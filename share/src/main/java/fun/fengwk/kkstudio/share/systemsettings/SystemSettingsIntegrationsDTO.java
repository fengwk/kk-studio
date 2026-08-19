package fun.fengwk.kkstudio.share.systemsettings;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * integrations section：外部媒体/生成集成的非敏感运行参数，每个集成都是必填嵌套对象。
 *
 * <p>秘密凭据（API key / bearer token / instanceId 等）不进入任何集成 DTO。
 */
@Data
public class SystemSettingsIntegrationsDTO {

  private ComfyuiDTO comfyui;

  private OpenCliHubDTO openCliHub;

  private SeedanceDTO seedance;

  private GptImage2DTO gptImage2;

  private MiniMaxH3DTO minimaxH3;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown system settings integrations field: " + name);
  }

  /** {@code integrations.comfyui}。 */
  @Data
  public static class ComfyuiDTO {

    private Boolean enabled;

    private String baseUrl;

    private Long connectTimeoutMillis;

    private Long readTimeoutMillis;

    private Long websocketTimeoutMillis;

    private Long maxInputFileBytes;

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
      throw new IllegalArgumentException(
          "unknown system settings integrations.comfyui field: " + name);
    }
  }

  /** {@code integrations.openCliHub}（不含 instanceId 等部署身份）。 */
  @Data
  public static class OpenCliHubDTO {

    private Boolean enabled;

    private String baseUrl;

    private Long connectTimeoutMillis;

    private Long requestTimeoutMillis;

    private Long longPollTimeoutMillis;

    private Integer streamBufferBytes;

    private Integer maxJsonResponseBytes;

    private Integer maxErrorResponseBytes;

    private Integer maxOutputChars;

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
      throw new IllegalArgumentException(
          "unknown system settings integrations.openCliHub field: " + name);
    }
  }

  /** {@code integrations.seedance}。 */
  @Data
  public static class SeedanceDTO {

    private Boolean enabled;

    private String workspaceId;

    private Integer retry;

    private Long hubExecutionTimeoutMillis;

    private Long statusPollIntervalMillis;

    private Long maxWaitMillis;

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
      throw new IllegalArgumentException(
          "unknown system settings integrations.seedance field: " + name);
    }
  }

  /** {@code integrations.gptImage2}。 */
  @Data
  public static class GptImage2DTO {

    private Boolean paidEnabled;

    private Integer askTimeoutSeconds;

    private Long hubExecutionTimeoutMillis;

    private Long maxWaitMillis;

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
      throw new IllegalArgumentException(
          "unknown system settings integrations.gptImage2 field: " + name);
    }
  }

  /** {@code integrations.minimaxH3}（不含 comfyBearerToken 等凭据）。 */
  @Data
  public static class MiniMaxH3DTO {

    private Boolean enabled;

    private String promptAgentName;

    private String promptEnvironmentName;

    private Long promptMaxWaitMillis;

    private String comfyBaseUrl;

    private Long comfyConnectTimeoutMillis;

    private Long comfyRequestTimeoutMillis;

    private Long comfyPollIntervalMillis;

    private Long comfyMaxWaitMillis;

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
      throw new IllegalArgumentException(
          "unknown system settings integrations.minimaxH3 field: " + name);
    }
  }
}
