/**
 * provider live test 子包。
 *
 * <p>维护约定： - 本子包只放真实环境集成测试（live test）。 - live test 依赖环境变量（TEST_*_BASE_URL / TEST_*_API_KEY）， 缺失时由
 * AbstractProviderLiveTestSupport 通过 JUnit Assumptions 自动跳过。 - 测试用例统一从 provider.fixtures 中的
 * live-cases.json 加载，避免硬编码。 - 因为依赖真实供应商响应，本层测试运行时间较长； 在 CI 中应考虑通过 -Dtest=...LiveTest 单独调度或由环境变量控制。
 *
 * @author fengwk
 */
package fun.fengwk.kkstudio.agent.provider.live;
