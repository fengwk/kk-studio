/**
 * 人工终端的服务端通知边界：固定 shell topic、严格 codec、READY 租约投递元数据与响应监听端口。
 *
 * <p>控制命令和事件复用 environment.terminal 的不可变模型；本包不创建 PTY、不解释 VT、不保存画面， 也不实现数据库或 WebSocket。宿主从认证连接取得
 * lease 与实例身份，通过统一通知总线投递到实际 owner 和 App 节点。
 */
package fun.fengwk.kkstudio.harness.environment.server.terminal;
