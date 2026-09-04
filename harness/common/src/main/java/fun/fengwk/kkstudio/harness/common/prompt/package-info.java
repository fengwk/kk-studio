/**
 * 严格 classpath prompt 模板原语。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.common.prompt.PromptTemplate} 仅支持 {@code ${name}}
 * 占位符且变量名严格匹配 {@code [A-Za-z_][A-Za-z0-9_]*}，渲染时变量集合必须精确匹配声明变量（拒绝未提供或多余变量），变量原样替换不做二次解析； {@link
 * fun.fengwk.kkstudio.harness.common.prompt.PromptTemplateLoader} 按资源路径缓存解析结果。
 *
 * <p>本包为纯只读模板原语，不负责业务 prompt 编排或动态表达式计算。
 */
package fun.fengwk.kkstudio.harness.common.prompt;
