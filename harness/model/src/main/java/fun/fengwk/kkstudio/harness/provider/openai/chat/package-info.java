/**
 * OpenAI Chat Completions 协议适配器与原生模型提供商实现。
 *
 * <p>基于 JDK HTTP/SSE 传输与 Jackson，实现标准的 OpenAI-compatible Chat Completions 交互，
 * 包括思考内容增量与重放保真、工具调用片段聚合与截断诊断、多种提示缓存模式、互斥用量度量及安全脱敏。
 */
package fun.fengwk.kkstudio.harness.provider.openai.chat;
