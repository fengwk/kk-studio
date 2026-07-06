package fun.fengwk.kkstudio.agent.model;

/**
 * ModelRegistry 负责模型信息的注册与查询。
 *
 * <p>语义说明： - set_model_info 事件中保存 provider、model 与 variant 名称。 - registry 返回完整 ModelInfo，再由运行时解析具体
 * variant。 - registry 是 model 目录与变体定义的抽象边界。
 *
 * @author fengwk
 */
public interface ModelRegistry {

  /**
   * 注册模型信息。
   *
   * @param modelInfo 模型信息
   */
  void registerModel(ModelInfo modelInfo);

  /**
   * 按 provider 与模型名查询模型信息。
   *
   * @param provider provider 名称
   * @param model 模型名称
   * @return 模型信息
   */
  ModelInfo getModel(String provider, String model);
}
