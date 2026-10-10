package fun.fengwk.kkstudio.platform.catalog.definition.builtin;

import lombok.AllArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionType;

/**
 * 启动期以 add-if-missing 方式初始化系统内置 Agent。
 *
 * <p>已存在的同名行是权威事实，绝不覆盖用户对 prompt/model/tools/skills 的编辑；未配置模型的内置 Agent 两列与 variant 同为 null。多实例
 * 并发启动时依赖 PostgreSQL 主键冲突收敛：冲突即视为其它实例已完成初始化，既有行仍是权威事实。
 */
@Component
@AllArgsConstructor
public class BuiltinAgentInitializer implements InitializingBean {

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentDefinitionConfigCodec configCodec;

  @Override
  public void afterPropertiesSet() {
    for (BuiltinAgentDefinitions.BuiltinAgent definition : BuiltinAgentDefinitions.definitions()) {
      if (agentDefinitionRepository.getByName(definition.name()) != null) {
        continue;
      }
      AgentDefinition builtin = new AgentDefinition();
      builtin.setName(definition.name());
      builtin.setType(AgentDefinitionType.BUILTIN);
      builtin.setSystemPrompt(definition.systemPrompt());
      builtin.setConfigJson(configCodec.encode(definition.config()));
      try {
        agentDefinitionRepository.create(builtin);
      } catch (DuplicateKeyException ignored) {
        // 并发初始化：其它实例已插入同名行；不覆盖既有定义。
      }
    }
  }
}
