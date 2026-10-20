/**
 * Agent 任务服务：子代理、插件、Issue 与 HTTP 提交共用的「创建 Thread + 接受输入 + 登记票据」入口，以及用票据观察、停止与读取结果。
 *
 * <p>{@link fun.fengwk.kkstudio.platform.agenttask.AgentTaskService}
 * 是唯一入口，创建委托运行时的统一创建原语，不持有自己的状态或表。
 */
package fun.fengwk.kkstudio.platform.agenttask;
