import { parentPort } from 'node:worker_threads'
import { runSearchJob, type SearchJob } from './search-core.js'

/**
 * 搜索 Worker 入口：独占线程执行真实搜索，只读文件、只回传纯数据。
 *
 * <p>取消与超时由主线程终止本 Worker（{@code terminate()}）实现，因此本线程不持有 AbortSignal；主线程负责结果落盘，Worker 不创建任何输出状态。
 */
const port = parentPort
if (port === null) {
  throw new Error('search worker must be started with a parent port')
}

port.on('message', (job: SearchJob) => {
  void runSearchJob(job).then((reply) => {
    port.postMessage(reply)
    // 正常回传后主动关闭端口让 Worker 自然退出（保留可收集的覆盖率）；取消/超时由主线程 terminate 硬终止。
    port.close()
  })
})
