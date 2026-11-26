import type { LocaleCatalog } from '@/shared/i18n/types'

/** 对话工作区：Thread 运行时、编辑区、历史分支、人工交互与首次配置引导。 */
export const chatCatalog = {
  'ai.runtime.action.approvalFailed': {
    'en-US': 'approve the tool call',
    'zh-CN': '审批工具调用',
  },
  'ai.chat.chatLabel': {
    'en-US': 'Chat',
    'zh-CN': '对话',
  },
  'ai.runtime.draft.defaultAgentMissing': {
    'en-US': 'Select an available default Agent for this space to send a message.',
    'zh-CN': '请为空间选择可用的默认 Agent 后再发送。',
  },
  'ai.runtime.draft.spaceSettings': { 'en-US': 'Space settings', 'zh-CN': '空间设置' },
  'ai.runtime.draft.agentSettings': { 'en-US': 'Agent settings', 'zh-CN': 'Agent 设置' },
  'ai.runtime.draft.sendUnknown': {
    'en-US': 'The send result is unknown. Retry to recover the original session.',
    'zh-CN': '未收到发送结果。重试以继续原会话。',
  },
  'ai.runtime.draft.sendConflict': {
    'en-US': 'This draft was already used to send a different message. Your message was not sent. Send again to start a new session.',
    'zh-CN': '此草稿已用于发送另一条消息。你的消息未发送，再次发送可创建新会话。',
  },
  'ai.runtime.draft.invalidFolder': {
    'en-US': 'This folder is no longer available. Choose another folder to send your message.',
    'zh-CN': '此目录已不可用。请选择其他目录后再发送。',
  },
  'ai.runtime.draft.environmentDefault': { 'en-US': 'Space default', 'zh-CN': '空间默认' },
  'ai.chat.noAgents': {
    'en-US': 'No Agents available',
    'zh-CN': '暂无可用 Agent',
  },
  'ai.chat.history.liveUpdatesStopped': {
    'en-US': 'Live updates stopped. This thread may be out of date. Reload to reconnect.',
    'zh-CN': '实时更新已停止，此 Thread 的内容可能已过期。请重新加载页面以恢复连接。',
  },
  'ai.runtime.branch.branchName': { 'en-US': 'Branch name', 'zh-CN': '分支名称' },
  'ai.runtime.branch.sessionName': { 'en-US': 'Session name', 'zh-CN': '会话名称' },
  'ai.runtime.branch.continueWithoutDraft': { 'en-US': 'Switch this pane to the new thread without carrying over unsent input.', 'zh-CN': '在当前窗格打开新 Thread，不带入未发送内容。' },
  'ai.runtime.branch.create': { 'en-US': 'Create', 'zh-CN': '创建' },
  'ai.runtime.branch.suggestedTitle': { 'en-US': 'Branch {{number}}', 'zh-CN': '分支 {{number}}' },
  'ai.runtime.branch.suggestedFork': { 'en-US': '{{title}} · Fork', 'zh-CN': '{{title}} · 分叉' },
  'ai.chat.branch.fromHere': {
    'en-US': 'Branch from here',
    'zh-CN': '从此处分支',
  },
  'ai.chat.branch.forkSession': {
    'en-US': 'Fork to new session',
    'zh-CN': '从此处新建会话',
  },
  'ai.chat.branch.nameConflict': {
    'en-US': 'This Session already has a thread or draft with this name. Choose another name.',
    'zh-CN': '此会话已有同名 Thread 或草稿，请换一个名称。',
  },
  'ai.runtime.composer.placeholder': {
    'en-US': 'Enter a task (/ for commands)',
    'zh-CN': '输入任务（/打开命令）',
  },
  'ai.runtime.composer.ariaLabel': {
    'en-US': 'Send a message to AI',
    'zh-CN': '给 AI 发送消息',
  },
  'ai.runtime.composer.send': {
    'en-US': 'Send message',
    'zh-CN': '发送消息',
  },
  'ai.runtime.composer.openCommands': {
    'en-US': 'Open commands',
    'zh-CN': '打开命令表',
  },
  'ai.runtime.composer.permission': {
    'en-US': 'Permission mode',
    'zh-CN': '权限模式',
  },
  'ai.runtime.composer.permissionOptions': {
    'en-US': 'Permission options',
    'zh-CN': '权限选项',
  },
  'ai.runtime.composer.permissionDefault': {
    'en-US': 'Default',
    'zh-CN': '默认',
  },
  'ai.runtime.composer.permissionYolo': {
    'en-US': 'YOLO',
    'zh-CN': 'YOLO',
  },
  'ai.runtime.composer.environment': {
    'en-US': 'Environment',
    'zh-CN': '环境',
  },
  'ai.runtime.composer.environmentOptions': {
    'en-US': 'Environment options',
    'zh-CN': '环境选项',
  },
  'ai.runtime.composer.environmentNone': {
    'en-US': 'None',
    'zh-CN': '无',
  },
  'ai.runtime.composer.model': {
    'en-US': 'Model',
    'zh-CN': '模型',
  },
  'ai.runtime.composer.modelVariant': {
    'en-US': 'Model and Variant',
    'zh-CN': '模型与预设',
  },
  'ai.runtime.composer.modelOptions': {
    'en-US': 'Model options',
    'zh-CN': '模型选项',
  },
  'ai.runtime.composer.modelSearch': {
    'en-US': 'Search models',
    'zh-CN': '搜索模型',
  },
  'ai.runtime.composer.modelSearchPlaceholder': {
    'en-US': 'Search models',
    'zh-CN': '搜索模型',
  },
  'ai.runtime.composer.variantOptions': {
    'en-US': 'Variant options',
    'zh-CN': '预设选项',
  },
  'ai.runtime.composer.settingsPendingLabel': {
    'en-US': 'Pending',
    'zh-CN': '待生效',
  },
  'ai.runtime.composer.settingsPendingTitle': {
    'en-US': 'This selection was submitted and is waiting to take effect.',
    'zh-CN': '当前选择已提交，等待生效。',
  },
  'ai.runtime.composer.strip': {
    'en-US': 'Attachments',
    'zh-CN': '附件',
  },
  'ai.runtime.composer.uploading': {
    'en-US': 'Uploading…',
    'zh-CN': '上传中…',
  },
  'ai.runtime.composer.completeUnknown': {
    'en-US': 'Status unknown, retry',
    'zh-CN': '状态未知，可重试',
  },
  'ai.runtime.composer.retryComplete': {
    'en-US': 'Retry complete {{name}}',
    'zh-CN': '重试完成 {{name}}',
  },
  'ai.runtime.composer.retryUpload': {
    'en-US': 'Retry uploading {{name}}',
    'zh-CN': '重试上传 {{name}}',
  },
  'ai.runtime.composer.uploadFailed': {
    'en-US': 'Upload failed',
    'zh-CN': '上传失败',
  },
  'ai.runtime.composer.uploadFailedDetail': {
    'en-US': 'Upload failed for {{name}}: {{reason}}',
    'zh-CN': '{{name}} 上传失败：{{reason}}',
  },
  'ai.runtime.composer.dismissNotification': {
    'en-US': 'Dismiss notification',
    'zh-CN': '关闭通知',
  },
  'ai.runtime.composer.removeAttachment': {
    'en-US': 'Remove attachment {{name}}',
    'zh-CN': '移除附件 {{name}}',
  },
  'ai.runtime.composer.imageTier': {
    'en-US': 'Image resolution for {{name}}',
    'zh-CN': '{{name}} 图片清晰度',
  },
  'ai.runtime.composer.imageTierHelp': {
    'en-US': 'The image is scaled to this size before upload.',
    'zh-CN': '图片会在上传前缩放到所选尺寸。',
  },
  'ai.runtime.composer.imageTierLoading': {
    'en-US': 'Loading…',
    'zh-CN': '读取中…',
  },
  'ai.runtime.composer.tierOriginal': {
    'en-US': 'Original',
    'zh-CN': '原图',
  },
  'ai.runtime.composer.fileTooLarge': {
    'en-US': '{{name}} exceeds the {{limit}} upload limit',
    'zh-CN': '{{name}} 超过 {{limit}} 上传限制',
  },
  'ai.runtime.command.palette': {
    'en-US': 'Command palette',
    'zh-CN': '命令表',
  },
  'ai.runtime.command.noMatch': {
    'en-US': 'No matching commands',
    'zh-CN': '无匹配命令',
  },
  'ai.runtime.command.unavailable': {
    'en-US': 'Currently unavailable',
    'zh-CN': '当前不可用',
  },
  'ai.runtime.command.meta': {
    'en-US': '{{enabled}} available / {{total}} total',
    'zh-CN': '可用 {{enabled}} / 共 {{total}}',
  },
  'ai.runtime.command.matches.one': {
    'en-US': ' · {{count}} match',
    'zh-CN': ' · 匹配 {{count}}',
  },
  'ai.runtime.command.matches.other': {
    'en-US': ' · {{count}} matches',
    'zh-CN': ' · 匹配 {{count}}',
  },
  'ai.runtime.command.hint': {
    'en-US': ' · ↑↓ select · Enter confirm',
    'zh-CN': ' · ↑↓ 选择 · Enter 确认',
  },
  'ai.runtime.command.upload': {
    'en-US': 'Upload files',
    'zh-CN': '上传文件',
  },
  'ai.runtime.command.stop': {
    'en-US': 'Stop the current Thread and restore unprocessed messages',
    'zh-CN': '停止当前 Thread 并恢复尚未处理的消息',
  },
  'ai.runtime.command.debug': {
    'en-US': 'Toggle the debug view',
    'zh-CN': '切换调试视图',
  },
  'ai.runtime.command.uploadLabel': {
    'en-US': 'upload',
    'zh-CN': 'upload',
  },
  'ai.runtime.command.models': {
    'en-US': 'Choose the model and Variant',
    'zh-CN': '选择模型与预设',
  },
  'ai.runtime.command.modelsLabel': {
    'en-US': 'models',
    'zh-CN': 'models',
  },
  'ai.runtime.command.stopLabel': {
    'en-US': 'stop',
    'zh-CN': 'stop',
  },
  'ai.runtime.command.debugLabel': {
    'en-US': 'debug',
    'zh-CN': 'debug',
  },
  'ai.runtime.childThread.readOnly': {
    'en-US': 'Read-only view',
    'zh-CN': '只读查看',
  },
  'ai.runtime.childThread.back': {
    'en-US': 'Back to parent agent',
    'zh-CN': '回到父 Agent',
  },
  'ai.runtime.childThread.environment': {
    'en-US': 'Environment: {{environment}} · Follows the main conversation',
    'zh-CN': '环境：{{environment}} · 跟随主对话',
  },
  'ai.runtime.childThread.environmentNone': {
    'en-US': 'Environment: not selected · Follows the main conversation',
    'zh-CN': '环境：未选择 · 跟随主对话',
  },
  'ai.runtime.childThread.copyThreadId': {
    'en-US': 'Copy Thread ID',
    'zh-CN': '复制 Thread ID',
  },
  'ai.runtime.childThread.threadIdCopied': {
    'en-US': 'Thread ID copied',
    'zh-CN': '已复制 Thread ID',
  },
  'ai.runtime.childThread.copyThreadIdFailed': {
    'en-US': 'Failed to copy Thread ID',
    'zh-CN': '无法复制 Thread ID',
  },
  'ai.runtime.compactionBranch.title': {
    'en-US': 'Compaction execution',
    'zh-CN': '压缩执行',
  },
  'ai.runtime.compactionBranch.back': {
    'en-US': 'Back to the conversation',
    'zh-CN': '返回主对话',
  },
  'ai.runtime.compactionBranch.stop': {
    'en-US': 'Stop',
    'zh-CN': '停止',
  },
  'ai.runtime.compactionBranch.stopping': {
    'en-US': 'Stopping…',
    'zh-CN': '正在停止…',
  },
  'ai.runtime.compactionBranch.stopFailed': {
    'en-US': 'The compaction execution could not be stopped.',
    'zh-CN': '无法停止本次压缩执行。',
  },
  'ai.runtime.compactionBranch.generating': {
    'en-US': 'Generating summary…',
    'zh-CN': '正在生成摘要…',
  },
  'ai.runtime.compactionBranch.candidate': {
    'en-US': 'Candidate summary',
    'zh-CN': '候选摘要',
  },
  'ai.runtime.compactionBranch.empty': {
    'en-US': 'No summary output',
    'zh-CN': '暂无摘要输出',
  },
  'ai.runtime.compactionBranch.diagnostics': {
    'en-US': 'Diagnostics',
    'zh-CN': '诊断',
  },
  'ai.runtime.compactionBranch.loadFailed': {
    'en-US': 'This execution could not be loaded.',
    'zh-CN': '无法加载本次执行。',
  },
  'ai.runtime.compactionBranch.inheritedContext': {
    'en-US': 'Inherited context: {{tokens}} tokens',
    'zh-CN': '继承的上下文：{{tokens}} tokens',
  },
  'ai.runtime.compactionBranch.instruction': {
    'en-US': 'Compaction instruction',
    'zh-CN': '压缩指令',
  },
  'ai.runtime.compactionBranch.modelCalls': {
    'en-US': 'Model calls',
    'zh-CN': '模型调用',
  },
  'ai.runtime.compactionBranch.toolErrors': {
    'en-US': 'Disabled tool calls',
    'zh-CN': '被停用的工具调用',
  },
  'ai.runtime.agentTree.panel': {
    'en-US': 'Active subagents',
    'zh-CN': '活跃子 Agent',
  },
  'ai.runtime.agentTree.activeCount': {
    'en-US': '{{count}} active',
    'zh-CN': '{{count}} 个活跃',
  },
  'ai.runtime.agentTree.error': {
    'en-US': 'Agent relationships failed to load',
    'zh-CN': 'Agent 关系加载失败',
  },
  'ai.runtime.agentTree.refreshFailed': {
    'en-US': 'Agent relationships failed to refresh',
    'zh-CN': 'Agent 关系刷新失败',
  },
  'ai.runtime.agentTree.retry': {
    'en-US': 'Retry',
    'zh-CN': '重试',
  },
  'ai.runtime.agentTree.counts': {
    'en-US': 'turns: {{turns}} · tools: {{tools}}',
    'zh-CN': '轮次：{{turns}} · 工具：{{tools}}',
  },
  'ai.runtime.agentTree.outcome.COMPLETED': {
    'en-US': 'Completed',
    'zh-CN': '已完成',
  },
  'ai.runtime.agentTree.outcome.FAILED': {
    'en-US': 'Failed',
    'zh-CN': '失败',
  },
  'ai.runtime.agentTree.outcome.STOPPED': {
    'en-US': 'Stopped',
    'zh-CN': '已停止',
  },
  'ai.runtime.agentTree.outcome.CANCELLED': {
    'en-US': 'Cancelled',
    'zh-CN': '已取消',
  },
  'ai.runtime.thread.loading': {
    'en-US': 'Loading conversation…',
    'zh-CN': '正在加载会话…',
  },
  'ai.runtime.thread.transcript': {
    'en-US': 'Conversation messages',
    'zh-CN': '会话消息',
  },
  'ai.runtime.thread.loadFailed': {
    'en-US': 'Conversation failed to load',
    'zh-CN': '会话加载失败',
  },
  'ai.runtime.thread.unavailable': {
    'en-US': 'This thread no longer exists. Saved history is read-only.',
    'zh-CN': '此 Thread 已不存在。已保留的历史记录仅供查看。',
  },
  'ai.runtime.thread.empty': {
    'en-US': 'Send a message to start the conversation',
    'zh-CN': '发送消息，开始对话',
  },
  'ai.runtime.thread.keyboardHint': {
    'en-US': 'Enter to send · Shift+Enter for a new line',
    'zh-CN': 'Enter 发送 · Shift+Enter 换行',
  },
  'ai.runtime.thread.error': {
    'en-US': 'Error',
    'zh-CN': '错误',
  },
  'ai.runtime.thread.dismissError': {
    'en-US': 'Dismiss error',
    'zh-CN': '关闭错误',
  },
  'ai.runtime.thread.status': {
    'en-US': 'Thread status',
    'zh-CN': 'Thread 状态',
  },
  'ai.runtime.thread.widgetZone': {
    'en-US': 'Thread activity',
    'zh-CN': 'Thread 活动',
  },
  'ai.runtime.thread.queue': {
    'en-US': 'Queued messages',
    'zh-CN': '等待处理的消息',
  },
  'ai.runtime.thread.queued': {
    'en-US': 'queued',
    'zh-CN': '排队中',
  },
  'ai.runtime.thread.queuedCustomMessage': {
    'en-US': 'Pending supplementary requirement',
    'zh-CN': '待处理补充要求',
  },
  'ai.runtime.thread.working': {
    'en-US': 'Working...',
    'zh-CN': '执行中',
  },
  'ai.runtime.tool.preparing': { 'en-US': 'Preparing call', 'zh-CN': '准备调用中' },
  'ai.runtime.tool.queued': { 'en-US': 'Queued', 'zh-CN': '排队中' },
  'ai.runtime.tool.environment': {
    'en-US': 'Waiting for environment {{environment}} to come online',
    'zh-CN': '等待环境 {{environment}} 上线',
  },
  'ai.runtime.tool.approval': { 'en-US': 'Waiting for approval', 'zh-CN': '等待审批' },
  'ai.runtime.tool.input': { 'en-US': 'Waiting for input', 'zh-CN': '等待输入' },
  'ai.runtime.tool.waitingChild': {
    'en-US': 'Waiting for subagent result',
    'zh-CN': '等待子 Agent 结果',
  },
  'ai.runtime.tool.running': { 'en-US': 'Running', 'zh-CN': '执行中' },
  'ai.runtime.tool.succeeded': { 'en-US': 'Succeeded', 'zh-CN': '成功' },
  'ai.runtime.tool.task.running': { 'en-US': 'Subagent running', 'zh-CN': '子 Agent 运行中' },
  'ai.runtime.tool.task.completed': { 'en-US': 'Completed', 'zh-CN': '已完成' },
  'ai.runtime.tool.task.failed': { 'en-US': 'Failed', 'zh-CN': '失败' },
  'ai.runtime.tool.task.cancelled': { 'en-US': 'Cancelled', 'zh-CN': '已取消' },
  'ai.runtime.tool.timedOut': { 'en-US': 'Timed out', 'zh-CN': '执行超时' },
  'ai.runtime.tool.failed': { 'en-US': 'Failed', 'zh-CN': '失败' },
  'ai.runtime.tool.cancelled': { 'en-US': 'Stopped', 'zh-CN': '已停止' },
  'ai.runtime.tool.unknown': { 'en-US': 'Execution outcome unknown', 'zh-CN': '执行结果未知' },
  // 只读消息容器。
  'ai.runtime.disclosure.expandDetails': { 'en-US': 'Expand details', 'zh-CN': '展开详情' },
  'ai.runtime.disclosure.collapseDetails': { 'en-US': 'Collapse details', 'zh-CN': '收起详情' },
  'ai.runtime.disclosure.fullDetails': { 'en-US': 'Full details', 'zh-CN': '完整详情' },
  'ai.runtime.disclosure.storedLogPath': { 'en-US': 'Saved log: {{path}}', 'zh-CN': '已保存的日志：{{path}}' },
  'ai.runtime.disclosure.outputOmitted': { 'en-US': '… Earlier output was omitted', 'zh-CN': '… 更早的输出已省略' },
  'ai.runtime.disclosure.truncated': {
    'en-US': '… truncated',
    'zh-CN': '… 已截断',
  },
  'ai.runtime.disclosure.longLineTruncated': {
    'en-US': '… Long line truncated',
    'zh-CN': '… 长行已截断',
  },
  'ai.runtime.disclosure.moreLine': {
    'en-US': '… {{count}} more line',
    'zh-CN': '… 下方还有 {{count}} 行',
  },
  'ai.runtime.disclosure.moreLines': {
    'en-US': '… {{count}} more lines',
    'zh-CN': '… 下方还有 {{count}} 行',
  },
  'ai.runtime.disclosure.moreLineTruncated': {
    'en-US': '… {{count}} more line, truncated',
    'zh-CN': '… 下方还有 {{count}} 行，长行已截断',
  },
  'ai.runtime.disclosure.moreLinesTruncated': {
    'en-US': '… {{count}} more lines, truncated',
    'zh-CN': '… 下方还有 {{count}} 行，长行已截断',
  },
  'ai.runtime.disclosure.earlierLine': {
    'en-US': '… {{count}} earlier line',
    'zh-CN': '… 上方还有 {{count}} 行',
  },
  'ai.runtime.disclosure.earlierLines': {
    'en-US': '… {{count}} earlier lines',
    'zh-CN': '… 上方还有 {{count}} 行',
  },
  'ai.runtime.disclosure.earlierLineTruncated': {
    'en-US': '… {{count}} earlier line; long line truncated',
    'zh-CN': '… 上方还有 {{count}} 行，长行已截断',
  },
  'ai.runtime.disclosure.earlierLinesTruncated': {
    'en-US': '… {{count}} earlier lines; long line truncated',
    'zh-CN': '… 上方还有 {{count}} 行，长行已截断',
  },
  'ai.runtime.disclosure.viewSubagentExecution': {
    'en-US': 'View subagent execution',
    'zh-CN': '查看子 Agent 执行',
  },
  'ai.runtime.mutation.reviewDiff': { 'en-US': 'Review diff', 'zh-CN': '审查 Diff' },
  'ai.runtime.mutation.preparing': {
    'en-US': 'Preparing diff review…',
    'zh-CN': '正在准备审查 Diff…',
  },
  'ai.runtime.mutation.inputLabel': {
    'en-US': 'Proposed changes',
    'zh-CN': '拟修改内容',
  },
  'ai.runtime.mutation.oldString': {
    'en-US': 'Original text:',
    'zh-CN': '原文本：',
  },
  'ai.runtime.mutation.newString': {
    'en-US': 'New text:',
    'zh-CN': '新文本：',
  },
  'ai.runtime.mutation.changeOf': {
    'en-US': 'Change {{index}} of {{total}}',
    'zh-CN': '第 {{index}}/{{total}} 处修改',
  },
  'ai.runtime.mutation.replaceAll': {
    'en-US': 'replace all',
    'zh-CN': '替换全部',
  },
  'ai.runtime.mutation.emptyFile': {
    'en-US': 'Empty file',
    'zh-CN': '空文件',
  },
  'ai.runtime.mutation.reviewMissing': {
    'en-US': 'Edit review missing or invalid; cannot allow.',
    'zh-CN': '未获取到有效的编辑审查信息，无法允许。',
  },
  'ai.runtime.mutation.lossyDecode.write.one': {
    'en-US': 'The original file contains 1 byte sequence that is not valid {{encoding}},'
      + ' shown as �. If it is still present, it is saved as U+FFFD, so its original bytes'
      + ' cannot be recovered.',
    'zh-CN': '原文件有 1 处字节序列不是有效的 {{encoding}}，显示为 �；保存后若仍保留，该位置会写为 U+FFFD，原始字节无法恢复。',
  },
  'ai.runtime.mutation.lossyDecode.write.other': {
    'en-US': 'The original file contains {{count}} byte sequences that are not valid'
      + ' {{encoding}}, shown as �. Those still present are saved as U+FFFD, so their original'
      + ' bytes cannot be recovered.',
    'zh-CN': '原文件有 {{count}} 处字节序列不是有效的 {{encoding}}，显示为 �；保存后仍保留的这些位置会写为 U+FFFD，原始字节无法恢复。',
  },
  'ai.runtime.mutation.lossyDecode.edit.one': {
    'en-US': 'The original file contains 1 byte sequence that is not valid {{encoding}},'
      + ' shown as �. Unchanged text keeps its original bytes; any � in the replacement'
      + ' text is saved as U+FFFD.',
    'zh-CN': '原文件有 1 处字节序列不是有效的 {{encoding}}，显示为 �；未修改的内容保留原始字节，替换内容中的 � 会写为 U+FFFD。',
  },
  'ai.runtime.mutation.lossyDecode.edit.other': {
    'en-US': 'The original file contains {{count}} byte sequences that are not valid'
      + ' {{encoding}}, shown as �. Unchanged text keeps its original bytes; any � in the'
      + ' replacement text is saved as U+FFFD.',
    'zh-CN': '原文件有 {{count}} 处字节序列不是有效的 {{encoding}}，显示为 �；未修改的内容保留原始字节，替换内容中的 � 会写为 U+FFFD。',
  },
  'ai.interaction.subagentSource': { 'en-US': 'subagent', 'zh-CN': '子 Agent' },
  'ai.interaction.openSubagent': { 'en-US': 'Open subagent', 'zh-CN': '打开子 Agent' },
  'ai.runtime.thread.status.RUNNING': {
    'en-US': 'Running',
    'zh-CN': '运行中',
  },
  'ai.runtime.thread.status.EXIT_PENDING': {
    'en-US': 'Exit pending',
    'zh-CN': '等待退出确认',
  },
  'ai.runtime.thread.status.WAITING': {
    'en-US': 'Waiting for async result',
    'zh-CN': '等待异步结果',
  },
  'ai.runtime.thread.status.IDLE': {
    'en-US': 'Idle',
    'zh-CN': '空闲',
  },
  'ai.runtime.thread.phase.generating': {
    'en-US': 'Generating',
    'zh-CN': '正在生成',
  },
  'ai.runtime.thread.phase.executingTool': {
    'en-US': 'Executing tool',
    'zh-CN': '正在执行工具',
  },
  'ai.runtime.thread.phase.materializing': {
    'en-US': 'Preparing result',
    'zh-CN': '正在整理结果',
  },
  'ai.runtime.thread.phase.nextStep': {
    'en-US': 'Preparing next step',
    'zh-CN': '准备下一步',
  },
  'ai.runtime.thread.executionControl.STOPPED': {
    'en-US': 'Stopped',
    'zh-CN': '已停止',
  },
  'ai.runtime.thread.exitPending': {
    'en-US': 'There is not yet evidence that this execution has exited',
    'zh-CN': '尚未取得该执行已退出的证据',
  },
  'ai.runtime.unconfirmedExecutions.title': {
    'en-US': 'Unconfirmed executions',
    'zh-CN': '未确认退出执行',
  },
  'ai.runtime.unconfirmedExecutions.hint': {
    'en-US': 'These executions have not confirmed exit. Abandoning only stops waiting for confirmation; it does not stop local or remote execution.',
    'zh-CN': '这些执行尚未确认退出。放弃等待只停止等待确认，不会终止本地或远端执行。',
  },
  'ai.runtime.unconfirmedExecutions.hintRunnable': {
    'en-US': 'There is not yet evidence that these executions have exited. Stop the Thread before deciding whether to abandon waiting.',
    'zh-CN': '尚未取得这些执行已退出的证据。停止 Thread 后才能选择是否放弃等待。',
  },
  'ai.runtime.unconfirmedExecutions.stopAndHandle': {
    'en-US': 'Stop and handle',
    'zh-CN': '停止并处理',
  },
  'ai.runtime.unconfirmedExecutions.itemKind': {
    'en-US': 'Type {{kind}}',
    'zh-CN': '类型 {{kind}}',
  },
  'ai.runtime.unconfirmedExecutions.itemAttempt': {
    'en-US': 'Attempt {{attempt}}',
    'zh-CN': '第 {{attempt}} 次尝试',
  },
  'ai.runtime.unconfirmedExecutions.itemExecutionId': {
    'en-US': 'Execution {{executionId}}',
    'zh-CN': '执行 {{executionId}}',
  },
  'ai.runtime.unconfirmedExecutions.abandon': {
    'en-US': 'Abandon waiting',
    'zh-CN': '放弃等待',
  },
  'ai.runtime.unconfirmedExecutions.abandonItem': {
    'en-US': 'Abandon waiting for {{kind}} attempt {{attempt}}',
    'zh-CN': '放弃等待 {{kind}} 第 {{attempt}} 次尝试',
  },
  'ai.runtime.unconfirmedExecutions.confirmTitle': {
    'en-US': 'Abandon waiting for {{kind}} attempt {{attempt}}?',
    'zh-CN': '放弃等待 {{kind}} 第 {{attempt}} 次尝试？',
  },
  'ai.runtime.unconfirmedExecutions.confirmDescription': {
    'en-US': 'Execution {{executionId}} will no longer be waited on for exit confirmation. This action does not terminate the local or remote execution; it may continue producing side effects, and recovery may overlap with it.',
    'zh-CN': '执行 {{executionId}} 将不再等待退出确认。此操作不会终止本地或远端执行；执行可能继续产生副作用，恢复也可能与其重叠。',
  },
  'ai.runtime.unconfirmedExecutions.confirmAction': {
    'en-US': 'Abandon waiting',
    'zh-CN': '放弃等待',
  },
  'ai.runtime.unconfirmedExecutions.stale': {
    'en-US': 'The Thread changed or this execution is no longer unconfirmed. The latest snapshot was loaded; choose again from the current list.',
    'zh-CN': 'Thread 已变化，或该执行已不再未确认。已加载最新快照，请按当前列表重新选择。',
  },
  'ai.runtime.unconfirmedExecutions.failed': {
    'en-US': 'Failed to abandon waiting: {{error}}',
    'zh-CN': '放弃等待失败：{{error}}',
  },
  'ai.runtime.message.stopped': {
    'en-US': 'Stopped',
    'zh-CN': '已停止',
  },
  'ai.runtime.message.copyAll': {
    'en-US': 'Copy all',
    'zh-CN': '复制全文',
  },
  'ai.runtime.message.assistantFailed': {
    'en-US': 'Assistant response failed',
    'zh-CN': '助手回复失败',
  },
  'ai.runtime.thinking.expand': {
    'en-US': 'Expand thinking',
    'zh-CN': '展开思考',
  },
  'ai.runtime.thinking.collapse': {
    'en-US': 'Collapse thinking',
    'zh-CN': '收起思考',
  },
  'ai.runtime.message.modelRequestFailed': {
    'en-US': 'Model request failed',
    'zh-CN': '模型请求失败',
  },
  'ai.runtime.message.modelFailureTransient': {
    'en-US': 'The model service is temporarily unavailable',
    'zh-CN': '模型服务暂时不可用',
  },
  'ai.runtime.message.modelFailureContextLimit': {
    'en-US': 'Context exceeds the model limit',
    'zh-CN': '上下文超出模型限制',
  },
  'ai.runtime.message.modelFailureRequestBodyLimit': {
    'en-US': 'Request body exceeds the model limit',
    'zh-CN': '请求体超出模型限制',
  },
  'ai.runtime.message.modelFailureMediaLimit': {
    'en-US': 'Attached media exceeds the model limit',
    'zh-CN': '附件媒体超出模型限制',
  },
  'ai.runtime.message.modelFailureAuthentication': {
    'en-US': 'Model authentication failed',
    'zh-CN': '模型认证失败',
  },
  'ai.runtime.message.modelFailureBilling': {
    'en-US': 'Account balance or quota exhausted',
    'zh-CN': '账户余额或配额不足',
  },
  'ai.runtime.message.modelFailureInvalidRequest': {
    'en-US': 'The model rejected the request',
    'zh-CN': '模型拒绝了该请求',
  },
  'ai.runtime.message.modelFailureInvalidResponse': {
    'en-US': 'The model returned an unusable response',
    'zh-CN': '模型返回了无法处理的响应',
  },
  'ai.runtime.message.modelFailureCancelled': {
    'en-US': 'The model request was cancelled',
    'zh-CN': '模型请求已取消',
  },
  'ai.runtime.message.modelFailureNoAvailableModel': {
    'en-US': 'No model is available',
    'zh-CN': '没有可用的模型',
  },
  'ai.runtime.message.modelFailureHttpStatus': {
    'en-US': 'HTTP {{status}}',
    'zh-CN': 'HTTP {{status}}',
  },
  'ai.runtime.message.modelFailureRawResponse': {
    'en-US': 'Raw response',
    'zh-CN': '原始响应',
  },
  'ai.runtime.message.modelFailureCopyRawResponse': {
    'en-US': 'Copy raw response',
    'zh-CN': '复制原始响应',
  },
  'ai.runtime.message.noUsage': {
    'en-US': 'No usage',
    'zh-CN': '无用量',
  },
  'ai.runtime.message.attemptNumber': {
    'en-US': 'Request #{{attempt}}',
    'zh-CN': '请求 #{{attempt}}',
  },
  'ai.runtime.message.retryScheduled': {
    'en-US': 'Retry in {{seconds}}s (request #{{nextAttempt}})',
    'zh-CN': '{{seconds}} 秒后重试（请求 #{{nextAttempt}}）',
  },
  'ai.runtime.message.retryScheduledHistory': {
    'en-US': 'Retry scheduled (request #{{nextAttempt}})',
    'zh-CN': '已安排重试（请求 #{{nextAttempt}}）',
  },
  'ai.runtime.message.retryingNow': {
    'en-US': 'Retrying (request #{{nextAttempt}})',
    'zh-CN': '正在重试（请求 #{{nextAttempt}}）',
  },
  'ai.runtime.message.attachment': {
    'en-US': '{{type}} attachment',
    'zh-CN': '{{type}} 附件',
  },
  'ai.runtime.message.binaryPayload': {
    'en-US': 'binary payload',
    'zh-CN': '二进制内容',
  },
  'ai.runtime.message.downloadResource': {
    'en-US': 'Download {{name}}',
    'zh-CN': '下载 {{name}}',
  },
  'ai.runtime.message.resourceUnavailable': {
    'en-US': 'Resource unavailable',
    'zh-CN': '资源不可用',
  },
  'ai.runtime.message.previewResource': {
    'en-US': 'Preview {{name}}',
    'zh-CN': '预览 {{name}}',
  },
  'ai.runtime.message.closeResourcePreview': {
    'en-US': 'Close media preview',
    'zh-CN': '关闭媒体预览',
  },
  'ai.runtime.message.openResource': {
    'en-US': 'Open original {{name}}',
    'zh-CN': '打开原件 {{name}}',
  },
  'ai.runtime.status.environmentUnavailableText': {
    'en-US': '{{name}} · unavailable',
    'zh-CN': '{{name}} · 不可用',
  },
  'ai.runtime.status.environmentText': {
    'en-US': '{{name}}',
    'zh-CN': '{{name}}',
  },
  'ai.runtime.status.environmentTitle': {
    'en-US': 'Environment: {{name}}',
    'zh-CN': '环境：{{name}}',
  },
  'ai.runtime.status.environmentUnavailableTitle': {
    'en-US': 'Environment: {{name}} · unavailable',
    'zh-CN': '环境：{{name}} · 不可用',
  },
  'ai.runtime.status.environmentNoneText': {
    'en-US': 'No environment',
    'zh-CN': '未选择环境',
  },
  'ai.runtime.status.noData': {
    'en-US': '—',
    'zh-CN': '—',
  },
  'ai.runtime.status.contextText': {
    'en-US': 'ctx {{used}}/{{total}}',
    'zh-CN': '上下文 {{used}}/{{total}}',
  },
  'ai.runtime.status.contextUsageTitleKnown': {
    'en-US': 'Context occupancy, latest model call estimate: ~{{used}} / {{total}} tokens',
    'zh-CN': '上下文占用，最近一次模型调用估算：约 {{used}} / {{total}} tokens',
  },
  'ai.runtime.status.contextUsageTitleUnknown': {
    'en-US': 'Context occupancy: not yet measured, limit {{total}} tokens',
    'zh-CN': '上下文占用：尚未测量，上限 {{total}} tokens',
  },
  'ai.runtime.status.usageTokensDetail': {
    'en-US': 'Uncached input: {{input}} tokens; output: {{output}} tokens; reasoning: {{reasoning}} tokens',
    'zh-CN': '未缓存输入：{{input}} tokens；输出：{{output}} tokens；推理：{{reasoning}} tokens',
  },
  'ai.runtime.status.usageCacheDetail': {
    'en-US': 'Cache read: {{cacheRead}} tokens; write: {{cacheWrite}} tokens',
    'zh-CN': '缓存读取：{{cacheRead}} tokens；缓存写入：{{cacheWrite}} tokens',
  },
  'ai.runtime.status.usageCostDetail': {
    'en-US': 'Estimated cost: {{cost}}; cache hit rate: {{cache}}; generation speed: {{speed}}',
    'zh-CN': '估算费用：{{cost}}；缓存命中率：{{cache}}；生成速度：{{speed}}',
  },
  'ai.runtime.notification.entry.SUBAGENT_RESULTTitle': {
    'en-US': 'Subagent result',
    'zh-CN': '子 Agent 结果',
  },
  'ai.runtime.notification.entry.TASK_BUDGETTitle': {
    'en-US': 'Task budget reminder',
    'zh-CN': '任务预算提醒',
  },
  'ai.runtime.notification.entry.unknownTitle': {
    'en-US': 'System notification',
    'zh-CN': '系统通知',
  },
  'ai.runtime.notification.entry.source': {
    'en-US': 'Source',
    'zh-CN': '来源',
  },
  'ai.runtime.notification.entry.cancelled': {
    'en-US': 'Cancelled',
    'zh-CN': '已取消',
  },
  'ai.runtime.notification.entry.partial': {
    'en-US': 'Partial result',
    'zh-CN': '部分结果',
  },
  'ai.runtime.notification.entry.subagentTask': {
    'en-US': 'Task',
    'zh-CN': '任务',
  },
  'ai.runtime.notification.entry.subagentState.completed': {
    'en-US': 'Returned',
    'zh-CN': '已返回',
  },
  'ai.runtime.notification.entry.viewSubagentExecution': {
    'en-US': 'View execution',
    'zh-CN': '查看执行',
  },
  'ai.runtime.notification.entry.invalidReceipt': {
    'en-US': 'The subagent receipt is not a valid XML envelope and cannot be displayed.',
    'zh-CN': '子 Agent 回执不是合法的 XML 信封，无法展示。',
  },
  'ai.runtime.notification.entry.emptyText': {
    'en-US': 'No text content was delivered with this notification.',
    'zh-CN': '该通知没有附带文本内容。',
  },
  'ai.runtime.task.state.error': {
    'en-US': 'Failed',
    'zh-CN': '失败',
  },
  'ai.runtime.task.subagent': {
    'en-US': 'Subagent',
    'zh-CN': '子 Agent',
  },
  'ai.runtime.entry.rootText': {
    'en-US': 'The conversation was created.',
    'zh-CN': '会话已创建。',
  },
  'ai.runtime.entry.rootSettings': {
    'en-US': 'Conversation started · Agent: {{agent}} · Model: {{model}} · Environment: {{environment}}',
    'zh-CN': '会话开始 · Agent: {{agent}} · 模型: {{model}} · 环境: {{environment}}',
  },
  'ai.runtime.entry.noEnvironment': {
    'en-US': 'none',
    'zh-CN': '无',
  },
  'ai.runtime.entry.invalidSettings': {
    'en-US': 'Settings snapshot could not be read',
    'zh-CN': '配置快照不可解析',
  },
  'ai.runtime.entry.agentChanged': {
    'en-US': 'Agent changed to {{agent}}',
    'zh-CN': 'Agent 改为 {{agent}}',
  },
  'ai.runtime.entry.modelChanged': {
    'en-US': 'Model changed to {{model}}',
    'zh-CN': '模型改为 {{model}}',
  },
  'ai.runtime.entry.modelFallback': {
    'en-US': '{{model}} is unavailable; this turn used the agent default model {{fallback}}',
    'zh-CN': '{{model}} 不可用，本轮改用 Agent 默认模型 {{fallback}}',
  },
  'ai.runtime.entry.environmentChanged': {
    'en-US': 'Environment changed to {{environment}}',
    'zh-CN': '环境改为 {{environment}}',
  },
  'ai.runtime.entry.environmentDetached': {
    'en-US': 'Environment detached',
    'zh-CN': '环境已解除',
  },
  'ai.runtime.entry.unknownType': {
    'en-US': 'unknown type',
    'zh-CN': '未知类型',
  },
  'ai.runtime.entry.unknownRole': {
    'en-US': 'Unknown role',
    'zh-CN': '未知角色',
  },
  'ai.runtime.entry.emptyAssistant': {
    'en-US': 'Empty response',
    'zh-CN': '返回内容为空',
  },
  'ai.runtime.entry.emptyMessage': {
    'en-US': 'This message has no displayable content.',
    'zh-CN': '此消息没有可展示内容。',
  },
  'ai.runtime.entry.unsupportedTitle': {
    'en-US': 'Unrecognized message',
    'zh-CN': '无法识别消息',
  },
  'ai.runtime.entry.unsupportedRoleText': {
    'en-US': 'Message role not supported: {{role}}.',
    'zh-CN': '暂不支持的消息角色：{{role}}。',
  },
  'ai.runtime.entry.unsupportedText': {
    'en-US': 'Message role or data is invalid.',
    'zh-CN': '消息角色或数据无效。',
  },
  'ai.runtime.entry.unknownTitle': {
    'en-US': 'Unrecognized Entry: {{type}}',
    'zh-CN': '未识别 Entry：{{type}}',
  },
  'ai.runtime.entry.unknownText': {
    'en-US': 'This entry type cannot be displayed.',
    'zh-CN': '无法显示此类型的记录。',
  },
  'ai.runtime.entry.compactionTitle': {
    'en-US': 'Context compacted',
    'zh-CN': '上下文已压缩',
  },
  'ai.runtime.entry.compactionRunning': {
    'en-US': 'Compacting context…',
    'zh-CN': '正在压缩上下文…',
  },
  'ai.runtime.entry.compactionFailed': {
    'en-US': 'Context compaction failed',
    'zh-CN': '上下文压缩失败',
  },
  'ai.runtime.entry.compactionStopped': {
    'en-US': 'Context compaction stopped',
    'zh-CN': '上下文压缩已停止',
  },
  'ai.runtime.entry.compactionRunningHint': {
    'en-US': 'Earlier messages are being summarized.',
    'zh-CN': '正在整理以上内容。',
  },
  'ai.runtime.entry.compactionStoppedHint': {
    'en-US': 'Stopped before a summary was produced.',
    'zh-CN': '未生成摘要即被停止。',
  },
  'ai.runtime.entry.compactionReason.failed': {
    'en-US': 'The summarization call failed.',
    'zh-CN': '摘要生成调用失败。',
  },
  'ai.runtime.entry.compactionReason.truncated': {
    'en-US': 'The summary was cut off by the output limit.',
    'zh-CN': '摘要因输出上限被截断。',
  },
  'ai.runtime.entry.compactionReason.filtered': {
    'en-US': 'The model provider filtered the summary.',
    'zh-CN': '摘要被提供商过滤。',
  },
  'ai.runtime.entry.compactionReason.invalidResponse': {
    'en-US': 'The model returned a response that cannot be used as a summary.',
    'zh-CN': '模型返回了无法作为摘要使用的结果。',
  },
  'ai.runtime.entry.compactionReason.emptySummary': {
    'en-US': 'The model returned an empty summary.',
    'zh-CN': '模型返回了空摘要。',
  },
  'ai.runtime.entry.compactionReason.budgetExceeded': {
    'en-US': 'The summary exceeded its token budget.',
    'zh-CN': '摘要超出了 token 预算。',
  },
  'ai.runtime.entry.compactionReason.contextLimit': {
    'en-US': 'The conversation still exceeded the model context limit.',
    'zh-CN': '上下文仍然超出模型上限。',
  },
  'ai.runtime.entry.compactionViewExecution': {
    'en-US': 'View execution',
    'zh-CN': '查看执行',
  },
  'ai.runtime.entry.compactionSeparator': {
    'en-US': 'Earlier messages are summarized above',
    'zh-CN': '以上内容已压缩为摘要',
  },
  'ai.runtime.entry.forkTitle': {
    'en-US': 'Thread forked',
    'zh-CN': '已从历史切点分叉',
  },
  'ai.runtime.entry.forkBranchTitle': {
    'en-US': 'Branched from history',
    'zh-CN': '已从历史切点创建分支',
  },
  'ai.runtime.entry.forkSessionTitle': {
    'en-US': 'Forked to new session',
    'zh-CN': '已从历史切点复制为新会话',
  },
  'ai.runtime.event.list': {
    'en-US': 'Events',
    'zh-CN': '事件',
  },
  'ai.runtime.event.empty': {
    'en-US': 'No events yet',
    'zh-CN': '暂无事件',
  },
  'ai.runtime.event.emptyText': {
    'en-US': 'No summary',
    'zh-CN': '无摘要',
  },
  'ai.runtime.event.abortedText': {
    'en-US': 'The assistant response was stopped by the user.',
    'zh-CN': '助手回复已被用户停止。',
  },
  'ai.runtime.event.unknownType': {
    'en-US': 'Unknown entry: {{type}}',
    'zh-CN': '未知条目：{{type}}',
  },
  'ai.runtime.event.unknownTool': {
    'en-US': 'unknown tool',
    'zh-CN': '未知工具',
  },
  'ai.runtime.event.detailTitle': {
    'en-US': 'Event details',
    'zh-CN': '事件详情',
  },
  'ai.runtime.event.closeDetail': {
    'en-US': 'Close event details',
    'zh-CN': '关闭事件详情',
  },
  'ai.runtime.debug.tabPreview': {
    'en-US': 'Request Preview',
    'zh-CN': '请求预览',
  },
  'ai.runtime.debug.tabEvents': {
    'en-US': 'Events',
    'zh-CN': '事件',
  },
  'ai.runtime.debug.tabDetail': {
    'en-US': 'Detail',
    'zh-CN': '详情',
  },
  'ai.runtime.debug.noSelection': {
    'en-US': 'No event or inspection target selected',
    'zh-CN': '未选择任何事件或检查项',
  },
  'ai.runtime.debug.noPreview': {
    'en-US': 'No request preview data',
    'zh-CN': '暂无请求预览数据',
  },
  'ai.runtime.debug.tabsAriaLabel': {
    'en-US': 'Debug views',
    'zh-CN': '调试视图',
  },
  'ai.runtime.debug.plannedToolEnvironment': {
    'en-US': 'Currently planned tool environment',
    'zh-CN': '当前规划的工具环境',
  },
  'ai.runtime.debug.close': {
    'en-US': 'Close Debug',
    'zh-CN': '关闭 Debug',
  },
  'ai.runtime.debug.noEnvironmentSelected': {
    'en-US': 'No environment selected',
    'zh-CN': '未选择环境',
  },
  'ai.runtime.debug.requestSnapshot': {
    'en-US': 'Frozen invocation input',
    'zh-CN': '冻结调用输入',
  },
  'ai.runtime.debug.requestSnapshotTitle': {
    'en-US': 'View the frozen canonical ProviderRequest for this invocation, not the raw HTTP payload',
    'zh-CN': '查看当前调用冻结的规范化 ProviderRequest，而非 HTTP 原始报文',
  },
  'ai.runtime.debug.planningError': {
    'en-US': 'Planning Error',
    'zh-CN': '规划错误',
  },
  'ai.runtime.debug.systemPromptTitle': {
    'en-US': 'System Prompt',
    'zh-CN': '系统提示词',
  },
  'ai.runtime.debug.emptyPrompt': {
    'en-US': 'Empty',
    'zh-CN': '空',
  },
  'ai.runtime.debug.toolsTitle': {
    'en-US': 'TOOLS',
    'zh-CN': '工具',
  },
  'ai.runtime.debug.toolsSent': {
    'en-US': 'included',
    'zh-CN': '纳入请求',
  },
  'ai.runtime.debug.toolAriaPrefix': {
    'en-US': 'Tool',
    'zh-CN': '工具',
  },
  'ai.runtime.debug.none': {
    'en-US': 'none',
    'zh-CN': '无',
  },
  'ai.runtime.debug.skillsTitle': {
    'en-US': 'SKILLS',
    'zh-CN': '技能',
  },
  'ai.runtime.debug.noSkills': {
    'en-US': 'No skills',
    'zh-CN': '暂无技能',
  },
  'ai.runtime.debug.skillAriaPrefix': {
    'en-US': 'Skill',
    'zh-CN': '技能',
  },
  'ai.runtime.debug.subagentsLabel': {
    'en-US': 'Subagents:',
    'zh-CN': '子 Agent：',
  },
  'ai.runtime.debug.subagentAriaPrefix': {
    'en-US': 'Subagent',
    'zh-CN': '子 Agent',
  },
  'ai.runtime.debug.cacheLabel': {
    'en-US': 'Cache:',
    'zh-CN': '缓存：',
  },
  'ai.runtime.debug.cacheAriaPrefix': {
    'en-US': 'Cache',
    'zh-CN': '缓存',
  },
  'ai.runtime.debug.cacheRetention.none': {
    'en-US': 'No explicit cache control',
    'zh-CN': '无显式缓存控制',
  },
  'ai.runtime.debug.cacheRetention.short': {
    'en-US': 'Short · SHORT',
    'zh-CN': '短期 · SHORT',
  },
  'ai.runtime.debug.cacheRetention.long': {
    'en-US': 'Long · LONG',
    'zh-CN': '长期 · LONG',
  },
  'ai.runtime.debug.envSupport.none': {
    'en-US': 'Platform only · NONE',
    'zh-CN': '平台独立 · NONE',
  },
  'ai.runtime.debug.envSupport.routed': {
    'en-US': 'Routed per call · ROUTED',
    'zh-CN': '按调用路由 · ROUTED',
  },
  'ai.runtime.debug.envSupport.required': {
    'en-US': 'Environment required · REQUIRED',
    'zh-CN': '需要环境 · REQUIRED',
  },
  'ai.runtime.debug.inspectorTitle': {
    'en-US': 'INSPECTOR',
    'zh-CN': '检查器',
  },
  'ai.runtime.debug.requestLabel': {
    'en-US': 'Frozen invocation input',
    'zh-CN': '冻结调用输入',
  },
  'ai.runtime.debug.historicalRequest': {
    'en-US': 'Historical Request Preview',
    'zh-CN': '历史请求预览',
  },
  'ai.runtime.debug.historicalRequestHint': {
    'en-US': 'Historical request preview: reconstructed under current catalog and provider definitions, not the original sent request',
    'zh-CN': '历史请求预览：按当前目录与提供商定义重建，非当时发送原文',
  },
  'ai.runtime.debug.historicalUnavailable': {
    'en-US': 'Historical request preview is not supported for this record',
    'zh-CN': '当前记录不支持历史请求预览',
  },
  'ai.runtime.debug.inspectorClose': {
    'en-US': 'Close inspector',
    'zh-CN': '关闭检查器',
  },
  'ai.runtime.debug.inspector.name': {
    'en-US': 'Name',
    'zh-CN': '名称',
  },
  'ai.runtime.debug.inspector.environmentSupport': {
    'en-US': 'Environment Support',
    'zh-CN': '环境支持',
  },
  'ai.runtime.debug.inspector.provenance': {
    'en-US': 'Contributor',
    'zh-CN': '来源',
  },
  'ai.runtime.debug.inspector.description': {
    'en-US': 'Description',
    'zh-CN': '描述',
  },
  'ai.runtime.debug.inspector.inputSchema': {
    'en-US': 'Input Schema JSON:',
    'zh-CN': '输入 Schema JSON：',
  },
  'ai.runtime.debug.inspector.skillName': {
    'en-US': 'Skill Name',
    'zh-CN': '技能名称',
  },
  'ai.runtime.debug.inspector.package': {
    'en-US': 'Package',
    'zh-CN': '包名',
  },
  'ai.runtime.debug.inspector.promptXml': {
    'en-US': 'Prompt XML:',
    'zh-CN': '提示词 XML：',
  },
  'ai.runtime.debug.inspector.cacheTitle': {
    'en-US': 'Cache Policy',
    'zh-CN': '缓存策略',
  },
  'ai.runtime.debug.inspector.cacheRetention': {
    'en-US': 'Retention',
    'zh-CN': '留存档位',
  },
  'ai.runtime.debug.inspector.cacheKey': {
    'en-US': 'Session Cache Key',
    'zh-CN': '会话缓存键',
  },
  'ai.runtime.debug.inspector.cacheProviderDisclaimer': {
    'en-US': 'No explicit cache control; provider may still cache automatically',
    'zh-CN': '无显式缓存控制，提供商仍可能自动缓存',
  },
  'ai.runtime.debug.inspector.subagentTools': {
    'en-US': 'Tools',
    'zh-CN': '工具',
  },
  'ai.runtime.debug.inspector.subagentSkills': {
    'en-US': 'Skills',
    'zh-CN': '技能',
  },
  'ai.runtime.debug.inspector.subagentSubagents': {
    'en-US': 'Subagents',
    'zh-CN': '子 Agent',
  },
  'ai.runtime.debug.inspector.subagentConfigurationJson': {
    'en-US': 'Configuration JSON:',
    'zh-CN': '配置 JSON：',
  },
  'ai.runtime.debug.noFrozenInvocation': {
    'en-US': 'No corresponding model invocation is currently readable. Frozen input is no longer available after the invocation is materialized and cleaned up.',
    'zh-CN': '当前没有可读取的对应模型调用；调用物化清理后不再提供冻结输入。',
  },
  'ai.runtime.debug.previewFailed': {
    'en-US': 'Failed to preview request',
    'zh-CN': '请求预览失败',
  },
  'ai.runtime.debug.inspector.historicalPreviewTitle': {
    'en-US': 'Historical Request Preview',
    'zh-CN': '历史请求预览',
  },
  'ai.runtime.debug.inspector.providerType': {
    'en-US': 'Provider Type',
    'zh-CN': '提供商类型',
  },
  'ai.runtime.debug.inspector.modelName': {
    'en-US': 'Model Name',
    'zh-CN': '模型名称',
  },
  'ai.runtime.debug.inspector.bodyByteSize': {
    'en-US': 'Payload Size',
    'zh-CN': '载荷大小',
  },
  'ai.runtime.debug.inspector.sourceHeadEntryId': {
    'en-US': 'Base Entry ID',
    'zh-CN': '基础 Entry ID',
  },
  'ai.runtime.debug.inspector.previewNotice': {
    'en-US': 'Notice',
    'zh-CN': '说明',
  },
  'ai.runtime.debug.inspector.requestBodyJson': {
    'en-US': 'Provider Request Body · JSON',
    'zh-CN': '提供商请求体 · JSON',
  },
  'ai.runtime.debug.previewLoading': {
    'en-US': 'Loading request preview...',
    'zh-CN': '正在加载请求预览...',
  },
  'ai.runtime.debug.previewLoadFailed': {
    'en-US': 'Failed to load request preview',
    'zh-CN': '请求预览加载失败',
  },
  'ai.runtime.debug.historicalNotice': {
    'en-US': 'Reconstructed from the recorded history under current catalog, provider and model definitions; not the original request sent at the time.',
    'zh-CN': '基于已记录历史，按当前目录、提供商与模型定义重建；不是当时实际发送的原文。',
  },
  'ai.runtime.debug.frozenNotice': {
    'en-US': 'Frozen canonical ProviderRequest for this invocation, not the provider HTTP body. It is readable at READY and does not prove a request has been sent.',
    'zh-CN': '当前调用冻结的规范化 ProviderRequest，不是提供商 HTTP 请求体；READY 阶段即可读取，不证明请求已经发送。',
  },
  'ai.runtime.debug.httpOriginalUnavailable': {
    'en-US': 'Raw HTTP wire payload is not captured',
    'zh-CN': '未捕获网络层原始 HTTP 报文',
  },
  'ai.runtime.debug.inspector.recordedAt': {
    'en-US': 'Recorded at planning time',
    'zh-CN': '规划时刻记录',
  },
  'ai.runtime.event.detail.entryId': {
    'en-US': 'Entry ID',
    'zh-CN': 'Entry ID',
  },
  'ai.runtime.event.detail.entryType': {
    'en-US': 'Entry type',
    'zh-CN': 'Entry 类型',
  },
  'ai.runtime.event.detail.createdAt': {
    'en-US': 'Time',
    'zh-CN': '时间',
  },
  'ai.runtime.event.detail.turn': {
    'en-US': 'Turn',
    'zh-CN': '回合',
  },
  'ai.runtime.event.detail.role': {
    'en-US': 'Role',
    'zh-CN': '角色',
  },
  'ai.runtime.event.detail.status': {
    'en-US': 'Status',
    'zh-CN': '状态',
  },
  'ai.runtime.event.detail.rawStatus': {
    'en-US': 'Raw status',
    'zh-CN': '原始状态',
  },
  'ai.runtime.event.detail.invocationId': {
    'en-US': 'Invocation ID',
    'zh-CN': '调用 ID',
  },
  'ai.runtime.event.detail.threadId': {
    'en-US': 'Thread ID',
    'zh-CN': '会话线程 ID',
  },
  'ai.runtime.event.detail.generationId': {
    'en-US': 'Generation ID',
    'zh-CN': '生成 ID',
  },
  'ai.runtime.event.detail.assistantEntryId': {
    'en-US': 'Assistant entry ID',
    'zh-CN': '助手 Entry ID',
  },
  'ai.runtime.event.detail.turnStartEntryId': {
    'en-US': 'Turn start entry ID',
    'zh-CN': '回合起始 Entry ID',
  },
  'ai.runtime.event.detail.requestHeadEntryId': {
    'en-US': 'Request head entry ID',
    'zh-CN': '请求头 Entry ID',
  },
  'ai.runtime.event.detail.attempt': {
    'en-US': 'Attempt',
    'zh-CN': '尝试次数',
  },
  'ai.runtime.event.detail.sequence': {
    'en-US': 'Sequence',
    'zh-CN': '序号',
  },
  'ai.runtime.event.detail.toolName': {
    'en-US': 'Tool',
    'zh-CN': '工具',
  },
  'ai.runtime.event.detail.toolCallId': {
    'en-US': 'Tool call ID',
    'zh-CN': '工具调用 ID',
  },
  'ai.runtime.event.detail.rendererKey': {
    'en-US': 'Renderer',
    'zh-CN': '渲染器',
  },
  'ai.runtime.event.detail.callIndex': {
    'en-US': 'Call index',
    'zh-CN': '调用索引',
  },
  'ai.runtime.event.detail.environment': {
    'en-US': 'Environment',
    'zh-CN': '运行环境',
  },
  'ai.runtime.event.detail.arguments': {
    'en-US': 'Arguments',
    'zh-CN': '参数',
  },
  'ai.runtime.event.detail.approval': {
    'en-US': 'Approval',
    'zh-CN': '审批',
  },
  'ai.runtime.event.detail.result': {
    'en-US': 'Result',
    'zh-CN': '结果',
  },
  'ai.runtime.event.detail.error': {
    'en-US': 'Error',
    'zh-CN': '错误',
  },
  'ai.runtime.event.detail.realtime': {
    'en-US': 'Realtime stream',
    'zh-CN': '实时流',
  },
  'ai.runtime.event.detail.partialText': {
    'en-US': 'Partial output',
    'zh-CN': '部分输出',
  },
  'ai.runtime.event.detail.partialThinking': {
    'en-US': 'Partial thinking',
    'zh-CN': '部分思考',
  },
  'ai.runtime.event.detail.errorCode': {
    'en-US': 'Error code',
    'zh-CN': '错误码',
  },
  'ai.runtime.event.detail.errorMessage': {
    'en-US': 'Error message',
    'zh-CN': '错误信息',
  },
  'ai.runtime.event.detail.failedAt': {
    'en-US': 'Failed at',
    'zh-CN': '失败时间',
  },
  'ai.runtime.event.detail.retryAt': {
    'en-US': 'Retry at',
    'zh-CN': '重试时间',
  },
  'ai.runtime.event.detail.chars': {
    'en-US': 'Streamed characters',
    'zh-CN': '已流式字符数',
  },
  'ai.runtime.event.detail.outcome': {
    'en-US': 'Outcome',
    'zh-CN': '结果',
  },
  'ai.runtime.event.detail.input': {
    'en-US': 'Input tokens',
    'zh-CN': '输入 tokens',
  },
  'ai.runtime.event.detail.output': {
    'en-US': 'Output tokens',
    'zh-CN': '输出 tokens',
  },
  'ai.runtime.event.detail.cacheRead': {
    'en-US': 'Cache read tokens',
    'zh-CN': '缓存读 tokens',
  },
  'ai.runtime.event.detail.cacheWrite': {
    'en-US': 'Cache write tokens',
    'zh-CN': '缓存写 tokens',
  },
  'ai.runtime.event.detail.reasoning': {
    'en-US': 'Reasoning tokens',
    'zh-CN': '推理 tokens',
  },
  'ai.runtime.event.detail.providerTotal': {
    'en-US': 'Provider total tokens',
    'zh-CN': '提供商总 tokens',
  },
  'ai.runtime.event.detail.cost': {
    'en-US': 'Cost',
    'zh-CN': '费用',
  },
  'ai.runtime.event.detail.forkMode': {
    'en-US': 'Fork mode',
    'zh-CN': '分叉模式',
  },
  'ai.runtime.event.detail.sourceEntryId': {
    'en-US': 'Source entry ID',
    'zh-CN': '来源 Entry ID',
  },
  'ai.runtime.event.status.PENDING': {
    'en-US': 'Pending',
    'zh-CN': '等待中',
  },
  'ai.runtime.event.status.RUNNING': {
    'en-US': 'Running',
    'zh-CN': '运行中',
  },
  'ai.runtime.event.status.COMPLETED': {
    'en-US': 'Completed',
    'zh-CN': '已完成',
  },
  'ai.runtime.event.status.FAILED': {
    'en-US': 'Failed',
    'zh-CN': '失败',
  },
  'ai.runtime.event.status.STOPPED': {
    'en-US': 'Stopped',
    'zh-CN': '已停止',
  },
  'ai.runtime.entry.assistantRequestFailed': {
    'en-US': 'Assistant request failed',
    'zh-CN': '助手请求失败',
  },
  'ai.runtime.action.requestFailed': {
    'en-US': 'Request failed',
    'zh-CN': '请求失败',
  },
  'ai.runtime.action.threadStateChanged': {
    'en-US': '{{action}}: Thread state changed — {{error}}. Refreshed; please try again',
    'zh-CN': '{{action}}：Thread 状态已变化 — {{error}}。已刷新，请重试',
  },
  'ai.runtime.action.updateYoloFailed': {
    'en-US': 'Failed to update YOLO',
    'zh-CN': '更新 YOLO 失败',
  },
  'ai.runtime.action.threadNotLoaded': {
    'en-US': 'Thread has not loaded yet',
    'zh-CN': 'Thread 尚未加载',
  },
  'ai.runtime.action.sendFailed': {
    'en-US': 'Send message failed',
    'zh-CN': '发送消息失败',
  },
  'ai.runtime.action.draftSaveFailed': {
    'en-US': 'Draft could not be saved; check browser storage and retry',
    'zh-CN': '草稿未能保存，请检查浏览器存储后重试',
  },
  'ai.runtime.action.draftLoadFailed': {
    'en-US': 'Draft could not be loaded; the editor may not show the latest draft',
    'zh-CN': '草稿加载失败，当前编辑区可能不是最新草稿',
  },
  'ai.runtime.action.draftRestoreFailed': {
    'en-US': 'Unsent input could not be restored to the editor; retry',
    'zh-CN': '未消费的输入未能恢复到编辑区，请重试',
  },
  'ai.runtime.action.draftWriteConflict': {
    'en-US': 'Draft out of sync: unsent input was restored elsewhere, your current edit is kept',
    'zh-CN': '草稿未同步：另一处已恢复未发送内容，当前输入已保留',
  },
  'ai.runtime.action.stopFailed': {
    'en-US': 'Stop failed',
    'zh-CN': '停止失败',
  },
  'ai.runtime.action.abandonedPendingNotice': {
    'en-US': 'The pending message was discarded locally. Commands already accepted by the server are not cancelled, and the message will not be resent automatically.',
    'zh-CN': '已放弃未决消息。这不会取消服务端可能已接受的命令，也不会自动重发。',
  },
  'ai.runtime.action.storageFailed': {
    'en-US': 'Could not save the send record; the message was not sent. Please retry.',
    'zh-CN': '无法保存发送记录，未发送消息；请重试。',
  },
  'ai.runtime.action.storageClearFailed': {
    'en-US': 'Could not clear the pending message record. Please retry.',
    'zh-CN': '清除未确认的消息记录失败，请重试。',
  },
  'ai.runtime.action.operationPending': {
    'en-US': 'This Thread has a pending operation; wait for it to finish or retry the original operation',
    'zh-CN': '当前 Thread 仍有未完成的操作，请等待完成或重试原操作',
  },
  'ai.interaction.pendingTitle': {
    'en-US': 'Pending Interactions',
    'zh-CN': '待处理事项',
  },
  'ai.interaction.loadFailed': {
    'en-US': 'Failed to load pending interactions',
    'zh-CN': '待处理交互加载失败',
  },
  'ai.interaction.loadMore': {
    'en-US': 'Load More',
    'zh-CN': '加载更多',
  },
  'ai.interaction.submit': {
    'en-US': 'Submit Answers',
    'zh-CN': '提交回答',
  },
  'ai.interaction.submitting': {
    'en-US': 'Submitting...',
    'zh-CN': '正在提交...',
  },
  'ai.interaction.decline': {
    'en-US': 'Decline',
    'zh-CN': '拒绝回答',
  },
  'ai.interaction.declined': {
    'en-US': 'Declined by user',
    'zh-CN': '已拒绝回答',
  },
  'ai.interaction.retry': {
    'en-US': 'Retry',
    'zh-CN': '重试',
  },
  'ai.interaction.customInputPlaceholder': {
    'en-US': 'Enter custom answer',
    'zh-CN': '输入自定义回答',
  },
  'ai.interaction.recommended': {
    'en-US': 'Recommended',
    'zh-CN': '推荐',
  },
  'ai.interaction.singleChoice': {
    'en-US': 'Single choice',
    'zh-CN': '单选',
  },
  'ai.interaction.multipleChoice': {
    'en-US': 'Multiple choice',
    'zh-CN': '多选',
  },
  'ai.interaction.questionnaireInvalid': {
    'en-US': 'This question cannot be displayed because its parameters could not be parsed.',
    'zh-CN': '无法显示这个问题：问卷参数无法解析。',
  },
  'ai.interaction.rawArguments': {
    'en-US': 'Raw parameters',
    'zh-CN': '原始参数',
  },
  'ai.interaction.nonRecoverable': {
    'en-US': 'This item has changed. Refresh to see the latest status.',
    'zh-CN': '此事项已变更，请刷新查看最新状态。',
  },
  'ai.interaction.conflictStillPending': {
    'en-US': 'The latest state was reloaded. Review it and submit again.',
    'zh-CN': '此事项的最新状态已重新载入，请重新确认后提交。',
  },
  'ai.interaction.allow': {
    'en-US': 'Allow',
    'zh-CN': '允许',
  },
  'ai.interaction.deny': {
    'en-US': 'Deny',
    'zh-CN': '拒绝',
  },
  'ai.interaction.denyReason': {
    'en-US': 'Reason for denial (optional)',
    'zh-CN': '拒绝原因（可选）',
  },
  'ai.interaction.denyReasonOptional': {
    'en-US': 'Enter a reason, if you want',
    'zh-CN': '可填写拒绝原因',
  },
  'ai.interaction.unreadableArguments': {
    'en-US': 'Tool parameters could not be read.',
    'zh-CN': '无法读取工具参数。',
  },
  'ai.interaction.environmentWaitingCount': {
    'en-US': '{{count}} tool calls waiting for {{environmentName}}',
    'zh-CN': '{{count}} 个工具调用等待 {{environmentName}} 上线',
  },
  'ai.resource.unsupported': {
    'en-US': 'Skill resources are not available yet',
    'zh-CN': '技能资源暂不可用',
  },
  'ai.interaction.queuedSameFile.one': {
    'en-US': '1 more change to this file will ask for approval after this one',
    'zh-CN': '处理后，此文件还有 1 个修改将依次请求审批',
  },
  'ai.interaction.queuedSameFile.other': {
    'en-US': '{{count}} more changes to this file will ask for approval after this one',
    'zh-CN': '处理后，此文件还有 {{count}} 个修改将依次请求审批',
  },
  'ai.runtime.tool.waitingForPreviousFileChange': {
    'en-US': 'Waiting for the earlier change to this file',
    'zh-CN': '等待前一个对此文件的修改',
  },
  'ai.resource.unavailable': {
    'en-US': 'Resource is unavailable in this session',
    'zh-CN': '此会话中资源不可用',
  },
  'ai.resource.image': {
    'en-US': 'Resource image',
    'zh-CN': '资源图片',
  },
  'ai.resource.preview': {
    'en-US': 'Preview',
    'zh-CN': '预览',
  },
  'ai.resource.download': {
    'en-US': 'Download',
    'zh-CN': '下载',
  },
  'ai.resource.openInIde': {
    'en-US': 'Open in IDE',
    'zh-CN': '在 IDE 中打开',
  },
  'ai.resource.openInIdePending': {
    'en-US': 'Opening in IDE…',
    'zh-CN': '正在 IDE 中打开…',
  },
  'ai.resource.openInIdeLimit': {
    'en-US': 'Opens a read-only UTF-8 text copy. Very large attachments may not be available.',
    'zh-CN': '打开只读 UTF-8 文本副本；过大的附件可能无法打开。',
  },
  'ai.resource.openInIde.failed': {
    'en-US': 'Could not open this attachment in the IDE.',
    'zh-CN': '无法在 IDE 中打开此附件。',
  },
  'ai.resource.openInIde.tooLarge': {
    'en-US': 'This text attachment exceeds the IDE preview size limit.',
    'zh-CN': '此文本附件超过 IDE 预览大小限制。',
  },
  'ai.resource.openInIde.unsupported': {
    'en-US': 'Only bounded UTF-8 text attachments can be opened in the IDE.',
    'zh-CN': 'IDE 仅支持打开大小受限的 UTF-8 文本附件。',
  },
  'ai.resource.loading': {
    'en-US': 'Loading resource',
    'zh-CN': '正在加载资源',
  },
  'ai.resource.previewFailed': {
    'en-US': 'Resource could not be loaded',
    'zh-CN': '资源加载失败',
  },
  'ai.resource.previewUnsupported': {
    'en-US': 'This resource cannot be previewed inline',
    'zh-CN': '此资源无法内联预览',
  },
  'ai.runtime.event.system.baseline': {
    'en-US': 'Baseline · {{sections}}',
    'zh-CN': '基线 · {{sections}}',
  },
  'ai.runtime.event.system.sectionUpdated': {
    'en-US': 'Section updated: {{name}}',
    'zh-CN': '分区已更新：{{name}}',
  },
  'ai.runtime.event.system.sectionRemoved': {
    'en-US': 'Section removed: {{name}}',
    'zh-CN': '分区已移除：{{name}}',
  },
  'ai.runtime.event.system.toolAdded': {
    'en-US': 'Tool added: {{name}}',
    'zh-CN': '工具已添加：{{name}}',
  },
  'ai.runtime.event.system.toolRemoved': {
    'en-US': 'Tool removed: {{name}}',
    'zh-CN': '工具已移除：{{name}}',
  },
  'ai.runtime.event.system.hostedToolAdded': {
    'en-US': 'Hosted tool added: {{name}}',
    'zh-CN': '托管工具已添加：{{name}}',
  },
  'ai.runtime.event.system.hostedToolRemoved': {
    'en-US': 'Hosted tool removed: {{name}}',
    'zh-CN': '托管工具已移除：{{name}}',
  },
  'ai.runtime.event.nativeCompaction.tokens': {
    'en-US': 'Tokens {{before}} → {{after}}',
    'zh-CN': 'Tokens {{before}} → {{after}}',
  },
  'ai.runtime.event.nativeCompaction.dropped': {
    'en-US': 'Dropped {{count}} tokens',
    'zh-CN': '已丢弃 {{count}} tokens',
  },
  'ai.runtime.event.nativeCompaction.duration': {
    'en-US': '{{duration}} ms',
    'zh-CN': '{{duration}} 毫秒',
  },
} satisfies LocaleCatalog
