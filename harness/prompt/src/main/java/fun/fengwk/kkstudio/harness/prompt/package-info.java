/**
 * 严格 classpath prompt 模板原语：{@link fun.fengwk.kkstudio.harness.prompt.PromptTemplate} 只支持 {@code
 * ${name}} 占位符、渲染必须恰好提供全部声明变量；{@link fun.fengwk.kkstudio.harness.prompt.PromptTemplateLoader}
 * 按资源路径缓存解析结果。压缩 prompt 与插件 prompt 都以此为准，不存在第二套模板引擎。
 */
package fun.fengwk.kkstudio.harness.prompt;
