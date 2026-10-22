package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 创建统一 Agent 模型（MODEL 或 GROUP）的请求体。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentModelCreateDTO extends AgentModelEditablePropertiesDTO {}
