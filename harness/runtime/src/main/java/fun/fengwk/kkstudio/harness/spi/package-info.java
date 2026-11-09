/**
 * Runtime 对基础设施暴露的窄端口。
 *
 * <p>端口不泄漏 Spring、JDBC 或 HTTP 类型。PostgreSQL HarnessStore / Work 是持久化事实；PostgreSQL notification
 * adapter 只提供 lossy live overlay。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.spi.ToolGateway} 是工具 execution 端口：先做纯本地 admission（Admitted
 * / Busy / Rejected，均发生在任何请求发出之前），再在调用线程上阻塞执行至终态，取消即中断运行线程；Runtime 在请求发出前持久化 RUNNING，并以所有权围栏拦截
 * 陈旧的终态提交。端口接收的 {@link fun.fengwk.kkstudio.harness.spi.ToolInvocationRequest} 是一次调用的 transient
 * 执行请求：只冻结 ToolCall 与 binding 供 preflight、审批预览与实际执行共用，随调用传递、绝不持久化。{@link
 * fun.fengwk.kkstudio.harness.spi.RealtimeEventSink} 是唯一的 realtime 输出端口，{@link
 * fun.fengwk.kkstudio.harness.spi.HarnessThreadChangeSource} 是内部观察者订阅 Thread version change 的纯 wake
 * 源（只表示「需要回读」，不携带 version）。{@link fun.fengwk.kkstudio.harness.spi.AcceptancePreflight} 是调用方提供的命令
 * 物化端口：Runtime 只在接受事务内以参数形式调用它（Attachment → durable RESOURCE），不持有实现。{@link
 * fun.fengwk.kkstudio.harness.spi.ToolResultSizeLimits} 是 Tool 结果的编码尺寸契约：内核持久化与平台网关对 canonical JSON
 * 字节预算共用同一组上限，不引入配置框架依赖。
 *
 * <p>专用契约按依赖的能力分子包：{@code spi.store} 是唯一持久化边界，{@code spi.driver} 是 Agent Driver 契约，{@code spi.work}
 * 是两者共同引用的 Work 邮箱值契约（{@code Work}、{@code WorkTarget}、{@code WorkTargetType} 与已认领快照 {@code
 * ClaimedWork}，不是公开 api）；{@code spi.execution} 是本地执行的物理生命周期端口（PREPARED 登记、物理句柄、取消、退出证明、排空与上一进程恢复）；
 * 部署配置与策略的现读端口按域放在 {@code spi.compaction}，重试配置直接使用 JDK {@code Supplier}/{@code Function}。Tool
 * 权限策略与其配置端口属于平台实现，不在内核 SPI 中。
 */
package fun.fengwk.kkstudio.harness.spi;
