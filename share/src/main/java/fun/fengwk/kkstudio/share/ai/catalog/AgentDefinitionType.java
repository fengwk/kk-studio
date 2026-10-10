package fun.fengwk.kkstudio.share.ai.catalog;

/**
 * Agent definition 的所有权类型。
 *
 * <p>{@link #USER} 由用户创建，可自由编辑与删除，且必须有已配置模型；{@link #BUILTIN} 由系统持有身份，名称与存在性不可变，用户只能编辑其
 * prompt/model/tools/skills 等普通执行配置，并允许显式未配置模型。该类型只出现在读模型中，可编辑的 create/PUT 请求体不接受它。
 */
public enum AgentDefinitionType {
  USER,
  BUILTIN
}
