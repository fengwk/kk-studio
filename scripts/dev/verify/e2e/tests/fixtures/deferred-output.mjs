// 测试夹具（--import 预加载）：把 stdout/stderr 写入延迟到活定时器，模拟真实管道下的异步输出。
// 进程若用 process.exit 强制退出会丢弃这些 pending 写入；设置 exitCode 后自然退出才会等待 drain。
for (const stream of [process.stdout, process.stderr]) {
  const write = stream.write.bind(stream)
  stream.write = (...args) => {
    setTimeout(() => write(...args), 20)
    return true
  }
}
