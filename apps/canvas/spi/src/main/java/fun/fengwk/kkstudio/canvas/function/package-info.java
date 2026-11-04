/**
 * Canvas Function 能力 SPI：adapter、冻结运行计划、执行上下文与严格 codec 端口。
 *
 * <p>本模块只依赖 JDK、Common 与 Jackson 3 流式 API：args 与运行状态由严格的 {@link
 * fun.fengwk.kkstudio.canvas.function.CanvasJson} 模型表达，Spring、HTTP、持久化与宿主适配由 Canvas 应用与其它消费方实现。
 *
 * <p>插件经本包贡献 Function adapter，宿主在快照装配时把它们与内置 adapter 冻结成一份目录；SPI 不依赖 {@code platform}
 * 任何模块，也不认识插件宿主。
 */
package fun.fengwk.kkstudio.canvas.function;
