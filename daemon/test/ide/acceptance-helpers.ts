import { request } from 'node:http'
import WebSocket from 'ws'
import type { IdeStreamUpstream } from '../../src/contracts.js'

export interface UpstreamHttpResult {
  status: number
  headers: Record<string, string | string[] | undefined>
  body: string
}

/** Issues one real HTTP request through the resolved private code-server upstream. */
export function requestUpstream(upstream: IdeStreamUpstream): Promise<UpstreamHttpResult> {
  return new Promise((resolve, reject) => {
    const headers: Record<string, string | string[]> = {}
    for (const [name, value] of upstream.headers) {
      const existing = headers[name]
      headers[name] = existing === undefined ? value : [...(Array.isArray(existing) ? existing : [existing]), value]
    }
    if (upstream.cookie !== undefined) {
      headers.cookie = upstream.cookie
    }
    const request_ = request(
      {
        host: upstream.host,
        port: upstream.port,
        path: upstream.path,
        method: 'GET',
        headers,
      },
      (response) => {
        const chunks: Buffer[] = []
        response.on('data', (chunk: Buffer) => chunks.push(chunk))
        response.on('end', () => {
          upstream.release()
          resolve({
            status: response.statusCode ?? 0,
            headers: response.headers,
            body: Buffer.concat(chunks).toString('utf8'),
          })
        })
        response.on('error', reject)
      },
    )
    request_.on('error', (error) => {
      upstream.release()
      reject(error)
    })
    request_.end()
  })
}

/** Opens one real WebSocket through the resolved private upstream and returns the first message. */
export function exchangeUpstreamWebSocket(upstream: IdeStreamUpstream, message: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const headers: Record<string, string> = {}
    for (const [name, value] of upstream.headers) {
      headers[name] = value
    }
    if (upstream.cookie !== undefined) {
      headers.cookie = upstream.cookie
    }
    const socket = new WebSocket(`ws://${upstream.host}:${upstream.port}${upstream.path}`, {
      headers,
      ...(upstream.protocols === undefined ? {} : { protocols: upstream.protocols }),
    })
    const timer = setTimeout(() => {
      socket.terminate()
      upstream.release()
      reject(new Error('upstream websocket timed out'))
    }, 30_000)
    socket.once('open', () => socket.send(message))
    socket.once('message', (data) => {
      clearTimeout(timer)
      socket.close()
      upstream.release()
      resolve(data.toString())
    })
    socket.once('error', (error) => {
      clearTimeout(timer)
      upstream.release()
      reject(error)
    })
  })
}
