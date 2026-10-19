import { parentPort } from 'node:worker_threads'
import { runSearchJob, type SearchJob } from './search-core.js'

/**
 * 搜索 Worker 入口：独占线程执行真实搜索，只读文件、只回传纯数据。
 *
 * <p>正常完成与协作式停止（stop）都在回传后主动关闭端口（port.close()），让 Worker 自然退出；
 * 父线程等待 Worker 退出后再交付结果。若收到 stop 后 5s 仍未响应，由父线程执行超时硬终止。
 */
const port = parentPort
if (port === null) {
  throw new Error('search worker must be started with a parent port')
}

port.on('message', (job: SearchJob) => {
  void runSearchJob(job)
    .then((reply) => {
      port.postMessage(reply)
      port.close()
    })
    .catch(() => {
      port.close()
    })
})
