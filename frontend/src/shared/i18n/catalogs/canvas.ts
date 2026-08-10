import type { LocaleCatalog } from '@/shared/i18n/types'

export const canvasCatalog = {
  'canvas.loading': {
    'en-US': 'Loading canvas',
    'zh-CN': '正在加载画布',
  },
  'canvas.library.eyebrow': {
    'en-US': 'Agent-native workspace',
    'zh-CN': 'Agent 原生工作区',
  },
  'canvas.library.headingLine1': {
    'en-US': 'Put ideas, references, and results',
    'zh-CN': '把想法、资料和结果',
  },
  'canvas.library.headingLine2': {
    'en-US': 'in one place.',
    'zh-CN': '放在同一个空间。',
  },
  'canvas.library.description': {
    'en-US': 'Organize durable resources and call typed image or video Functions.',
    'zh-CN': '组织持久化资源，并调用类型明确的图片或视频 Function。',
  },
  'canvas.library.title': {
    'en-US': 'Your canvases',
    'zh-CN': '你的画布',
  },
  'canvas.library.loading': {
    'en-US': 'Loading canvases',
    'zh-CN': '正在加载画布',
  },
  'canvas.library.loadError': {
    'en-US': 'Failed to load canvases: {{message}}',
    'zh-CN': '画布列表加载失败：{{message}}',
  },
  'canvas.library.createAria': {
    'en-US': 'Create a new canvas',
    'zh-CN': '创建新画布',
  },
  'canvas.library.createTitle': {
    'en-US': 'Create new canvas',
    'zh-CN': '创建新画布',
  },
  'canvas.library.createSubtitle': {
    'en-US': 'Blank canvas · Start now',
    'zh-CN': '空白画布 · 立即开始',
  },
  'canvas.library.revision': {
    'en-US': 'revision',
    'zh-CN': '修订版',
  },
  'canvas.library.realCanvas': {
    'en-US': 'Server snapshot',
    'zh-CN': '服务端快照',
  },
  'canvas.toast.library.created': {
    'en-US': 'Created canvas “{{title}}”',
    'zh-CN': '已创建画布「{{title}}」',
  },
  'canvas.toast.library.createError': {
    'en-US': 'Failed to create canvas',
    'zh-CN': '创建画布失败',
  },
  'canvas.toast.library.open': {
    'en-US': 'Open “{{title}}”',
    'zh-CN': '打开「{{title}}」',
  },
  'canvas.editor.ariaLabel': {
    'en-US': 'Canvas resource editor',
    'zh-CN': 'Canvas 资源编辑器',
  },
  'canvas.editor.library': {
    'en-US': 'Canvas library',
    'zh-CN': '画布库',
  },
  'canvas.editor.save.saving': {
    'en-US': 'Saving…',
    'zh-CN': '保存中…',
  },
  'canvas.editor.save.saved': {
    'en-US': 'Saved',
    'zh-CN': '已保存',
  },
  'canvas.editor.notFound': {
    'en-US': 'Canvas not found',
    'zh-CN': '画布不存在',
  },
  'canvas.editor.loadFailed': {
    'en-US': 'Failed to load canvas',
    'zh-CN': '画布加载失败',
  },
  'canvas.editor.loading': {
    'en-US': 'Loading canvas…',
    'zh-CN': '正在加载画布…',
  },
  'canvas.editor.backToLibrary': {
    'en-US': 'Back to canvas library',
    'zh-CN': '返回画布库',
  },
  'canvas.editor.retry': {
    'en-US': 'Retry',
    'zh-CN': '重试',
  },
  'canvas.editor.revision': {
    'en-US': 'Canvas revision',
    'zh-CN': '画布修订版',
  },
  'canvas.stage.ariaLabel': {
    'en-US': 'Infinite canvas. Drag resources, connect ResourceNodes to Function nodes, and drop media files to upload.',
    'zh-CN': '无限画布。拖动资源，将 ResourceNode 连接到 Function 节点，或拖入媒体文件上传。',
  },
  'canvas.stage.minimap': {
    'en-US': 'Canvas minimap',
    'zh-CN': '画布小地图',
  },
  'canvas.stage.selectionToolbar': {
    'en-US': 'Selected canvas actions',
    'zh-CN': '选区操作',
  },
  'canvas.stage.group': {
    'en-US': 'Group',
    'zh-CN': '分组',
  },
  'canvas.stage.ungroup': {
    'en-US': 'Ungroup / remove from group',
    'zh-CN': '解散 / 移出分组',
  },
  'canvas.stage.agent': {
    'en-US': 'Ask Agent',
    'zh-CN': '交给 Agent',
  },
  'canvas.stage.delete': {
    'en-US': 'Delete',
    'zh-CN': '删除',
  },
  'canvas.stage.hint.pan': {
    'en-US': 'pan',
    'zh-CN': '平移',
  },
  'canvas.stage.hint.tools': {
    'en-US': 'tools',
    'zh-CN': '工具',
  },
  'canvas.stage.hint.invokeAgent': {
    'en-US': 'invoke Agent',
    'zh-CN': '唤起 Agent',
  },
  'canvas.stage.zoomControls': {
    'en-US': 'Canvas zoom controls',
    'zh-CN': '画布缩放控制',
  },
  'canvas.stage.toolControls': {
    'en-US': 'Canvas tool controls',
    'zh-CN': '画布工具控制',
  },
  'canvas.stage.toolSelect': {
    'en-US': 'Select tool (V)',
    'zh-CN': '选择工具 (V)',
  },
  'canvas.stage.toolHand': {
    'en-US': 'Hand tool (H)',
    'zh-CN': '抓手工具 (H)',
  },
  'canvas.stage.zoomOut': {
    'en-US': 'Zoom out',
    'zh-CN': '缩小',
  },
  'canvas.stage.fitAll': {
    'en-US': 'Fit all content',
    'zh-CN': '适应全部内容',
  },
  'canvas.stage.resetZoom': {
    'en-US': 'Reset zoom to 100%',
    'zh-CN': '重置缩放为 100%',
  },
  'canvas.stage.zoomIn': {
    'en-US': 'Zoom in',
    'zh-CN': '放大',
  },
  'canvas.node.kind.image': {
    'en-US': 'Image reference',
    'zh-CN': '图片参考',
  },
  'canvas.node.kind.video': {
    'en-US': 'Video reference',
    'zh-CN': '视频参考',
  },
  'canvas.node.kind.audio': {
    'en-US': 'Audio reference',
    'zh-CN': '音频参考',
  },
  'canvas.node.kind.text': {
    'en-US': 'Text',
    'zh-CN': '文本',
  },
  'canvas.node.kind.resource': {
    'en-US': 'Resource',
    'zh-CN': '资源',
  },
  'canvas.node.kind.function': {
    'en-US': 'Function',
    'zh-CN': 'Function',
  },
  'canvas.node.renameAria': {
    'en-US': 'Node name',
    'zh-CN': '节点名称',
  },
  'canvas.node.editMarkdown': {
    'en-US': 'Edit Markdown',
    'zh-CN': '编辑 Markdown',
  },
  'canvas.node.emptyFunction': {
    'en-US': 'No resources until the Function succeeds',
    'zh-CN': 'Function 首次成功前暂无资源',
  },
  'canvas.node.empty': {
    'en-US': 'No resources yet',
    'zh-CN': '暂无资源',
  },
  'canvas.node.runReady': {
    'en-US': 'Ready',
    'zh-CN': '就绪',
  },
  'canvas.node.switcherAria': {
    'en-US': 'Resource index switcher',
    'zh-CN': '资源索引切换',
  },
  'canvas.node.previous': {
    'en-US': 'Previous resource',
    'zh-CN': '上一个资源',
  },
  'canvas.node.next': {
    'en-US': 'Next resource',
    'zh-CN': '下一个资源',
  },
  'canvas.node.viewResource': {
    'en-US': 'View resource {{index}}',
    'zh-CN': '查看资源 {{index}}',
  },
  'canvas.node.handleIn': {
    'en-US': 'Function reference input',
    'zh-CN': 'Function 引用输入',
  },
  'canvas.node.handleOut': {
    'en-US': 'Resource reference output',
    'zh-CN': '资源引用输出',
  },
  'canvas.generation.panelAria': {
    'en-US': 'Function generation panel',
    'zh-CN': 'Function 生成面板',
  },
  'canvas.generation.nodeKicker': {
    'en-US': 'Function',
    'zh-CN': 'Function',
  },
  'canvas.generation.status.ready': {
    'en-US': 'Ready',
    'zh-CN': '就绪',
  },
  'canvas.generation.status.running': {
    'en-US': 'Running',
    'zh-CN': '运行中',
  },
  'canvas.generation.status.succeeded': {
    'en-US': 'Succeeded',
    'zh-CN': '成功',
  },
  'canvas.generation.status.failed': {
    'en-US': 'Failed',
    'zh-CN': '失败',
  },
  'canvas.generation.status.cancelled': {
    'en-US': 'Cancelled',
    'zh-CN': '已取消',
  },
  'canvas.generation.expand': {
    'en-US': 'Expand panel',
    'zh-CN': '展开面板',
  },
  'canvas.generation.collapse': {
    'en-US': 'Collapse panel',
    'zh-CN': '收起面板',
  },
  'canvas.generation.close': {
    'en-US': 'Close panel',
    'zh-CN': '关闭面板',
  },
  'canvas.generation.modelLabel': {
    'en-US': 'Model',
    'zh-CN': '模型',
  },
  'canvas.generation.promptLabel': {
    'en-US': 'Prompt',
    'zh-CN': '提示词',
  },
  'canvas.generation.placeholder': {
    'en-US': 'Describe what you want to generate; type @ to reference a resource',
    'zh-CN': '描述你想生成的内容，输入 @ 引用资源',
  },
  'canvas.generation.segmentAria': {
    'en-US': 'Prompt segment {{index}}',
    'zh-CN': '提示词片段 {{index}}',
  },
  'canvas.generation.composerAria': {
    'en-US': 'Structured prompt',
    'zh-CN': '结构化提示词',
  },
  'canvas.generation.references': {
    'en-US': 'Reference assets',
    'zh-CN': '可用参考资源',
  },
  'canvas.generation.referenceCandidates': {
    'en-US': '@ reference candidates',
    'zh-CN': '@ 引用候选',
  },
  'canvas.generation.referenceNone': {
    'en-US': 'No candidates',
    'zh-CN': '没有可用引用',
  },
  'canvas.generation.referenceInsert': {
    'en-US': 'Insert reference {{label}}',
    'zh-CN': '插入参考 {{label}}',
  },
  'canvas.generation.referenceRemove': {
    'en-US': 'Remove {{label}}',
    'zh-CN': '删除 {{label}}',
  },
  'canvas.generation.referenceLimit': {
    'en-US': 'The model reference limit has been reached.',
    'zh-CN': '当前模型的参考资源数量已达到上限。',
  },
  'canvas.generation.submit': {
    'en-US': 'Start generation',
    'zh-CN': '开始生成',
  },
  'canvas.generation.cancel': {
    'en-US': 'Cancel',
    'zh-CN': '取消',
  },
  'canvas.generation.modelUnavailable': {
    'en-US': 'unavailable',
    'zh-CN': '不可用',
  },
  'canvas.generation.modelUnavailableOption': {
    'en-US': '{{label}} ({{reason}})',
    'zh-CN': '{{label}}（{{reason}}）',
  },
  'canvas.generation.runFailed': {
    'en-US': 'Generation failed; previous resources are kept',
    'zh-CN': '生成失败，已有资源已保留',
  },
  'canvas.media.fetchImage': {
    'en-US': 'Fetch original',
    'zh-CN': '获取原图',
  },
  'canvas.media.openImage': {
    'en-US': 'Open original',
    'zh-CN': '打开原图',
  },
  'canvas.media.downloadImage': {
    'en-US': 'Download original',
    'zh-CN': '下载原图',
  },
  'canvas.media.fetchVideo': {
    'en-US': 'Fetch original video',
    'zh-CN': '获取视频原件',
  },
  'canvas.media.openVideo': {
    'en-US': 'Open original video',
    'zh-CN': '打开视频原件',
  },
  'canvas.media.downloadVideo': {
    'en-US': 'Download original video',
    'zh-CN': '下载视频原件',
  },
  'canvas.media.fetchAudio': {
    'en-US': 'Fetch original audio',
    'zh-CN': '获取音频原件',
  },
  'canvas.media.openAudio': {
    'en-US': 'Open original audio',
    'zh-CN': '打开音频原件',
  },
  'canvas.media.downloadAudio': {
    'en-US': 'Download original audio',
    'zh-CN': '下载音频原件',
  },
  'canvas.media.playVideo': {
    'en-US': 'Play video {{name}}',
    'zh-CN': '播放视频 {{name}}',
  },
  'canvas.media.play': {
    'en-US': 'Play {{name}}',
    'zh-CN': '播放 {{name}}',
  },
  'canvas.media.playAudio': {
    'en-US': 'Play audio {{name}}',
    'zh-CN': '播放音频 {{name}}',
  },
  'canvas.media.loadAudio': {
    'en-US': 'Load audio',
    'zh-CN': '加载音频',
  },
  'canvas.media.loading': {
    'en-US': 'Loading…',
    'zh-CN': '加载中…',
  },
  'canvas.media.signing': {
    'en-US': 'Signing…',
    'zh-CN': '签名中…',
  },
  'canvas.media.signFailed': {
    'en-US': 'Original signing failed',
    'zh-CN': '原件签名失败',
  },
  'canvas.media.previewFailed': {
    'en-US': 'Preview failed to load',
    'zh-CN': '预览加载失败',
  },
  'canvas.media.loadingPreview': {
    'en-US': 'Loading preview…',
    'zh-CN': '加载预览…',
  },
  'canvas.media.waitingViewport': {
    'en-US': 'Waiting to enter viewport',
    'zh-CN': '等待进入视口',
  },
  'canvas.add.ariaLabel': {
    'en-US': 'Add resource, Function, or group',
    'zh-CN': '添加资源、Function 或分组',
  },
  'canvas.agent.thread.ariaLabel': {
    'en-US': 'Canvas Agent messages',
    'zh-CN': 'Canvas Agent 消息',
  },
  'canvas.agent.context.ariaLabel': {
    'en-US': 'Agent resource context',
    'zh-CN': 'Agent 资源上下文',
  },
  'canvas.agent.context.selection': {
    'en-US': 'Current selection',
    'zh-CN': '当前选区',
  },
  'canvas.agent.context.whole': {
    'en-US': 'Whole canvas',
    'zh-CN': '整张画布',
  },
  'canvas.agent.collapse': {
    'en-US': 'Collapse Agent messages',
    'zh-CN': '收起 Agent 消息',
  },
  'canvas.agent.prompt.ariaLabel': {
    'en-US': 'Describe a task for Agent',
    'zh-CN': '向 Agent 描述任务',
  },
  'canvas.agent.prompt.placeholder': {
    'en-US': 'Tell Agent what to do with the current resources…',
    'zh-CN': '告诉 Agent 如何处理当前资源…',
  },
  'canvas.agent.send': {
    'en-US': 'Send to Agent',
    'zh-CN': '发送给 Agent',
  },
} satisfies LocaleCatalog
