/**
 * provider 测试包覆盖 provider 接入层的分层验证。
 *
 * 当前分层：
 * - manager mapping：ProviderType -> Provider 实现映射。
 * - request mapping：ModelInfo / Variant / ToolInfo -> 底层请求。
 * - async bridge：流式回调桥接与 handle 取消语义。
 * - metadata / tool schema mapping：通用与供应商专有映射。
 * - HTTP contract：本地探针校验 path / header / body。
 * - live smoke：真实环境变量驱动的 provider 联调。
 *
 * 子包职责：
 * - provider.support：测试基座类（AbstractProviderTestSupport / Live / Contract）。
 *   不参与真正测试，仅供真正测试继承。
 * - provider.fixtures：测试 fixture 加载与 JSON 资源。
 * - provider.live：真实供应商集成测试，依赖环境变量；
 *   缺失环境变量时由 Assumptions 自动跳过。
 *
 * 维护约定：
 * - 真正测试类保持在 provider 根包，基座 / fixtures 沉到子包。
 * - 固定 prompt、期望片段等长篇测试数据按包结构抽取到 src/test/resources。
 * - 新增 provider 时，应同时补齐上述各层至少一个对应测试。
 */
package fun.fengwk.kkstudio.agent.provider;
