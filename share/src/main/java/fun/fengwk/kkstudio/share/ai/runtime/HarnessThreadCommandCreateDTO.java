package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

import java.util.List;

/**
 * Typed Thread mailbox command request。
 *
 * <p>{@code type} 是 discriminator（USER_MESSAGE / CUSTOM_MESSAGE / SET_AGENT / SET_MODEL /
 * SET_THINKING_LEVEL / SET_ACTIVE_TOOLS / SET_YOLO / SET_ENVIRONMENT）；mapper 按 discriminator 严格校验
 * required/forbidden 可选字段。{@code clientCommandId} 是稳定幂等键。
 */
@Data
public class HarnessThreadCommandCreateDTO {
  private String type;
  private String clientCommandId;
  private String content;
  private String role;
  private String agentName;
  private HarnessModelSelectionDTO model;
  private String thinkingLevel;
  private List<String> activeTools;
  private Boolean yoloEnabled;
  private String environmentId;
}
