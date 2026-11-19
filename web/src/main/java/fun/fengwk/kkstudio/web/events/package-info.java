/**
 * 浏览器单应用 WebSocket 事件通道（{@code /ws/events}）。
 *
 * <p>连接建立后的第一帧（严格 JSON）：
 *
 * <pre>{@code
 * {"type":"connected","eventNodeId":"<canonical UUID>","connectionId":"<canonical UUID>","heartbeatIntervalMillis":"<positive decimal>"}
 * }</pre>
 *
 * <p>{@code connected} 的 {@code heartbeatIntervalMillis} 是服务端实际使用的文本 heartbeat
 * 间隔（字符串形式的正十进制，便于客户端按同一 canonical 规则校验）；客户端以它推导失联预算（连续若干个间隔没有合法帧即重连），不写死默认值。
 *
 * <p>客户端帧（严格 JSON，字段集精确）：
 *
 * <pre>{@code
 * {"type":"subscribe","resource":{"kind":"thread"|"canvas"|"thread-tree","id":"<canonical UUID>"}}
 * {"type":"subscribe","resource":{"kind":"projects"|"interactions"|"environments"}}
 * {"type":"unsubscribe","resource":{...}}
 * {"type":"refresh","resource":{"kind":"thread-tree","id":"<canonical UUID>"}}
 * {"type":"ide-use","browserSessionId":"<canonical UUID>","revision":"<positive decimal>","views":[{"environmentId":"<canonical UUID>","viewId":"<canonical UUID>","claimId":"<canonical UUID>","bindingEpoch":"<positive decimal>"}]}
 * }</pre>
 *
 * <p>{@code ide-use} 至多 64 项，列表内 viewId 唯一；缺省 bindingEpoch 表示尚未 claim 的意图，不能用于续租。同一 revision
 * 相同内容幂等，不同内容拒绝；更高 revision 原子替换。每个连接固定一个 browserSessionId。
 *
 * <p>服务端帧：
 *
 * <pre>{@code
 * {"type":"subscribed","resource":{...},"cursor":"<canonical decimal>"}
 * {"type":"event","resource":{...},"name":"version"|"realtime"|"changed"|"revision"|"snapshot","data":{...},"cursor":"<optional canonical decimal>"}
 * {"type":"resync","resource":{...}}
 * {"type":"snapshot-ref","resource":{"kind":"thread","id":"<canonical UUID>"},"version":"<canonical decimal>"}
 * {"type":"snapshot-ref","resource":{"kind":"thread-tree","id":"<canonical UUID>"}}
 * {"type":"heartbeat"}
 * {"type":"ide-use-accepted","revision":"<positive decimal>"}
 * {"type":"error","code":"<string>","message":"<string>","resource"?:{...}}
 * }</pre>
 *
 * <p>{@code subscribed} 的 {@code cursor} 是订阅建立瞬间的 durable cursor（Thread version / Canvas
 * revision）；该 cursor 之后的事件保证送达，之前的由客户端随后拉取的 snapshot 覆盖。thread-tree 使用无 cursor 的 {@code snapshot}
 * 事件，{@code data} 固定为 {@code {nodes:[HarnessThreadTreeNodeDTO]}} 完整小快照；变化由服务端按执行根串行投影并合并尾随更新。每个本地
 * thread-tree 订阅登记后都强制请求当前完整快照，事件帧在 {@code subscribed} ack 帧之后才发送。{@code refresh} 只允许对当前连接已订阅的
 * thread-tree 资源使用，不改变引用计数；不支持的资源回 {@code INVALID_REFRESH_RESOURCE}，未订阅资源回 {@code
 * RESOURCE_NOT_SUBSCRIBED}。 快照投影或投递失败回资源级 {@code TREE_SNAPSHOT_FAILED}，不自动 resync 重试。
 *
 * <p>{@code snapshot-ref} 表示该资源的权威快照本身超出本连接单帧 UTF-8 预算，正文不在 wire 上：它与 {@code event}
 * 一样是该资源的逻辑交付（订阅仍有效），客户端必须回读权威 HTTP 事实。Thread 的 {@code version} 是顶层字段，取 快照的真实 DTO version（canonical
 * 非负十进制）而非合成 cursor；执行树不带 {@code version}。单帧放不下不等于发送积压，
 * 物理连接与其他资源不受影响，可发送的后续快照照常投递；只有队列帧数或待发字节总量越界才是真实过载。
 *
 * <p>非法帧（{@code INVALID_FRAME}）或发送过载 （{@code BACKPRESSURE}）时发送连接级 error 帧并关闭连接；未知资源只回资源级 {@code
 * RESOURCE_NOT_FOUND} 并保持连接。{@code ide-use} 冲突回连接级 {@code IDE_USE_REJECTED} 并保持连接。服务端每 15 秒发送原生
 * ping，45 秒没有有效 pong 即撤销该连接的 IDE 使用证明并关闭连接。
 */
package fun.fengwk.kkstudio.web.events;
