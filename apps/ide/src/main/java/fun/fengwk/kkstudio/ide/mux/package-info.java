/**
 * IDE 数据 wire v2 的严格 codec 与 transport-neutral mux 引擎。
 *
 * <p>每条物理 binary message 恰好一帧：12 字节头、九种类型、64 KiB 单帧上限。逻辑流由 Server 发起并携带 viewId、bindingEpoch、HTTP/WS
 * 模式和过滤后的请求元数据；流内保持 wire 顺序，管理帧优先但不能越过同流先前 DATA 的 END。
 *
 * <p>引擎持有每流 256 KiB、整连接 4 MiB 的双向 credit，按 {@code max(payloadLength, 512)} 精确计费。调用方只有在下游真实消费 {@link
 * fun.fengwk.kkstudio.ide.mux.MuxReceivedData} 后才归还 credit；RESET/关闭时，已消费但未归还的额度按连接级 WINDOW
 * 回收，已投递未消费片段保持占用直至消费，迟到 DATA 仍计入连接额度并在 WINDOW 物理写完成后释放。关闭流仅由单调 streamId 上界识别，不保留无限
 * tombstone。物理写失败立即 fail-stop，所有排队/等待 future 异常结束且不重放。
 *
 * <p>本包不依赖 Spring WebSocket，也不包含任何浏览器/HTTP/WS 适配器：未来 web 层负责建立受控数据连接、实现 {@link
 * fun.fengwk.kkstudio.ide.mux.MuxTransport}、调用 {@link
 * fun.fengwk.kkstudio.ide.mux.MuxConnection#receive(byte[])}，并处理 原生 Ping/Pong 与 HTTP 超时。包内不触碰服务、web
 * 或版本配置。
 */
package fun.fengwk.kkstudio.ide.mux;
