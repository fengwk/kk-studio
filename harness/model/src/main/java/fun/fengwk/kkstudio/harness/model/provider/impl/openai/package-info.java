/**
 * OpenAI 协议族的共享端点校验步骤。
 *
 * <p>Chat Completions 与 Responses 在各自子包内编码请求、重建历史块并消费流；本包只共享两种协议实际使用的 URI 校验原语，
 * 具体校验顺序和错误映射仍由各协议入口决定。
 */
package fun.fengwk.kkstudio.harness.model.provider.impl.openai;
