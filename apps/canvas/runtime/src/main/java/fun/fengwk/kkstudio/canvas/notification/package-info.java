/**
 * Canvas revision 通知纵向切片：{@code canvas_revision} 通道定义、revision 事件源与进程内 Hub。
 *
 * <p>{@link fun.fengwk.kkstudio.canvas.notification.CanvasVersionHub} 独占通道常量 {@code
 * canvas_revision}，写侧 {@code PostgresqlCanvasChangeNotifier} 与宿主 LISTEN 注册都引用它，不保留第二处定义。Hub 只读取
 * {@code canvas_document} 权威 revision 并做进程内 fan-out，不持有 LISTEN 连接、不认识浏览器传输：宿主注册 LISTEN 后把通知与 resync
 * 交给 Hub，再把产生的 version/revision 信号转发给浏览器。初始 cursor 来自持久表，丢通知、畸形 payload 与重连都由 resync 收敛。
 */
package fun.fengwk.kkstudio.canvas.notification;
