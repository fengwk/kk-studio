/**
 * 原生 OpenAI Responses API 流式协议提供商实现。
 *
 * <p>基于 JDK HTTP transport，直接处理 OpenAI {@code /responses} 端点的 SSE 事件， 提供确定性无状态回放（stateless
 * replay）、多工具调用、媒体输入、提示缓存（prompt cache）控制与细粒度 token 用量归一化。
 */
package fun.fengwk.kkstudio.harness.provider.openai.responses;
