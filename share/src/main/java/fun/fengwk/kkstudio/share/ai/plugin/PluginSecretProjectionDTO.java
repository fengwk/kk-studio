package fun.fengwk.kkstudio.share.ai.plugin;

import lombok.Data;

/** 单个已声明秘密字段的安全投影：只有说明与「是否已录入」布尔，绝无值、密文或长度。 */
@Data
public class PluginSecretProjectionDTO {

  /** schema 声明的字段说明。 */
  private String description;

  /** 该字段当前是否已录入秘密；缺主密钥时也如实为 true（不谎称未录入）。 */
  private boolean configured;
}
