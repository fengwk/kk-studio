import { constants as h2constants, type ServerHttp2Session, type ServerHttp2Stream } from 'node:http2'
import type { Duplex } from 'node:stream'

/** 关闭控制会话所需的 WebSocket 动作，单独抽出让关停顺序可被测试直接驱动。 */
export interface ControlWebSocket {
  close(): void
  terminate(): void
  prependListener(event: 'close' | 'error', listener: () => void): unknown
}

/**
 * 在 WebSocket 关闭/报错时先销毁它背后的 Duplex。
 *
 * <p>http2 用 JSStreamSocket 包装 createWebSocketStream 的 Duplex，只允许一个在途
 * write/shutdown。对端关闭 WebSocket 时，createWebSocketStream 会 push(null)；Node 随即在
 * 可读侧结束时半关闭 Duplex。ws 的 _final 在底层 socket 已消失时直接返回而不调用回调，
 * shutdown 请求会永久挂起；之后 http2 的任何写入都会在 JSStreamSocket.doWrite 触发
 * ERR_INTERNAL_ASSERTION。该异常从原生回调抛出，close() 里的 try/catch 拦不住，会让 Daemon
 * 进程崩溃。先销毁 Duplex 可让 socket 直接关闭，http2 走 socket_closed=true 的清理路径。
 */
export function guardControlStream(ws: ControlWebSocket, stream: Duplex): void {
  const destroyStream = (): void => {
    if (!stream.destroyed) {
      stream.destroy()
    }
  }
  ws.prependListener('close', destroyStream)
  ws.prependListener('error', destroyStream)
}

function peerEnded(stream: Duplex): boolean {
  return stream.destroyed || stream.readableEnded
}

/**
 * 立即关闭控制会话。
 *
 * <p>对端已经结束时只销毁底层流，不再让 http2 写 GOAWAY；连接仍然可用时先让 http2 发送
 * GOAWAY，再终止 WebSocket。
 */
export function abortControlSession(
  session: ServerHttp2Session | null,
  stream: Duplex,
  ws: ControlWebSocket,
): void {
  if (session !== null && !session.destroyed && !peerEnded(stream)) {
    try {
      session.destroy()
    } catch {}
  } else if (!stream.destroyed) {
    stream.destroy()
  }
  try {
    ws.terminate()
  } catch {}
}

/**
 * 优雅关闭控制会话：先提交 NO_ERROR GOAWAY，再关闭 WebSocket。
 *
 * <p>这里只提交 GOAWAY 而不调用 session.close()/destroy()：会话随 WebSocket 流关闭以
 * socket_closed=true 清理，避免 http2 在后续步骤再次写 GOAWAY。对端已经结束时直接降级为
 * 立即关闭。
 */
export function closeControlSession(
  session: ServerHttp2Session,
  stream: Duplex,
  ws: ControlWebSocket,
  controlStream: ServerHttp2Stream | null,
): void {
  try {
    controlStream?.end()
  } catch {}
  if (session.destroyed || peerEnded(stream)) {
    abortControlSession(session, stream, ws)
    return
  }
  try {
    session.goaway(h2constants.NGHTTP2_NO_ERROR)
  } catch {}
  try {
    ws.close()
  } catch {}
}
