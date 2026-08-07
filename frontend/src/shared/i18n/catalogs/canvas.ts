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
    'en-US': 'Create canvases, organize resources, and call Functions and Agents.',
    'zh-CN': '创建画布，组织资源，调用 Function 与 Agent。',
  },
  'canvas.library.researchLink': {
    'en-US': 'View research conclusions',
    'zh-CN': '查看调研结论',
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
    'en-US': 'Live canvas',
    'zh-CN': '真实画布',
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
  'canvas.toast.library.all': {
    'en-US': 'Showing all canvases',
    'zh-CN': '正在展示全部画布',
  },
  'canvas.toast.library.filterUpdated': {
    'en-US': 'Canvas filter updated',
    'zh-CN': '画布筛选已更新',
  },
  'canvas.editor.ariaLabel': {
    'en-US': 'Canvas editor for competitor research and product planning',
    'zh-CN': '竞品研究与产品方案画布编辑器',
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
  'canvas.editor.help': {
    'en-US': 'View keyboard shortcuts',
    'zh-CN': '查看快捷操作',
  },
  'canvas.editor.share': {
    'en-US': 'Share',
    'zh-CN': '分享',
  },
  'canvas.editor.export': {
    'en-US': 'Export',
    'zh-CN': '导出',
  },
  'canvas.editor.more': {
    'en-US': 'More editor actions',
    'zh-CN': '更多编辑器操作',
  },
  'canvas.toast.editor.share': {
    'en-US': 'Share link copied (prototype)',
    'zh-CN': '分享链接已复制（原型模拟）',
  },
  'canvas.toast.editor.export': {
    'en-US': 'PNG / PDF / JSON export will be available in the full product (prototype)',
    'zh-CN': '导出 PNG / PDF / JSON 将在完整产品中提供（原型模拟）',
  },
  'canvas.toast.editor.more': {
    'en-US': 'More editor actions will be available in the full product (prototype)',
    'zh-CN': '更多编辑器操作将在完整产品中提供（原型模拟）',
  },
  'canvas.stage.ariaLabel': {
    'en-US': 'Infinite canvas. Drag objects, scroll to pan, Ctrl or ⌘ + scroll to zoom, press Space or the middle mouse button to pan.',
    'zh-CN': '无限画布。拖动对象，普通滚轮平移，Ctrl 或 ⌘ + 滚轮缩放，按空格或中键平移。',
  },
  'canvas.stage.minimap': {
    'en-US': 'Canvas minimap',
    'zh-CN': '画布小地图',
  },
  'canvas.stage.selectionToolbar': {
    'en-US': 'Selected canvas object actions',
    'zh-CN': '所选画布对象操作',
  },
  'canvas.stage.edit': {
    'en-US': '✎ Edit',
    'zh-CN': '✎ 编辑',
  },
  'canvas.toast.stage.edit': {
    'en-US': 'Edit mode is ready (prototype)',
    'zh-CN': '编辑模式已准备就绪（原型模拟）',
  },
  'canvas.stage.agent': {
    'en-US': '✦ Ask Agent to handle',
    'zh-CN': '✦ 让 Agent 处理',
  },
  'canvas.toast.stage.agent': {
    'en-US': 'Agent has read the current selection',
    'zh-CN': 'Agent 已读取当前选区',
  },
  'canvas.stage.addContext': {
    'en-US': '⊙ Add to context',
    'zh-CN': '⊙ 加入上下文',
  },
  'canvas.toast.stage.addContext': {
    'en-US': 'Added {{count}} objects to the current context',
    'zh-CN': '已将 {{count}} 个对象加入当前上下文',
  },
  'canvas.stage.moreObjects': {
    'en-US': 'More object actions',
    'zh-CN': '更多对象操作',
  },
  'canvas.toast.stage.moreObjects': {
    'en-US': 'More object actions will be available in the inspector (prototype)',
    'zh-CN': '更多对象操作将在检查器中提供（原型模拟）',
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
  'canvas.stage.zoomOut': {
    'en-US': 'Zoom out',
    'zh-CN': '缩小',
  },
  'canvas.stage.fitAll': {
    'en-US': 'Fit all content',
    'zh-CN': '适应全部内容',
  },
  'canvas.stage.fitTitle': {
    'en-US': 'Fit view (0)',
    'zh-CN': '适应视图 (0)',
  },
  'canvas.stage.zoom100Title': {
    'en-US': '100% (1)',
    'zh-CN': '100% (1)',
  },
  'canvas.stage.zoomIn': {
    'en-US': 'Zoom in',
    'zh-CN': '放大',
  },
  'canvas.research.ariaLabel': {
    'en-US': 'Research and architecture notes',
    'zh-CN': '调研与架构说明',
  },
  'canvas.research.eyebrow': {
    'en-US': 'Product research',
    'zh-CN': '产品研究',
  },
  'canvas.research.title': {
    'en-US': 'Why is it designed this way?',
    'zh-CN': '为什么这样设计？',
  },
  'canvas.research.close': {
    'en-US': 'Close notes',
    'zh-CN': '关闭说明',
  },
  'canvas.research.tabs': {
    'en-US': 'Research content',
    'zh-CN': '调研内容',
  },
  'canvas.research.tab.research': {
    'en-US': 'Competitive insights',
    'zh-CN': '竞品洞察',
  },
  'canvas.research.tab.architecture': {
    'en-US': 'Solution foundation',
    'zh-CN': '方案基座',
  },
  'canvas.research.tab.roadmap': {
    'en-US': 'Phased roadmap',
    'zh-CN': '分期计划',
  },
  'canvas.research.section.positioning': {
    'en-US': 'Product positioning',
    'zh-CN': '产品定位',
  },
  'canvas.research.section.objectModel': {
    'en-US': 'Object model',
    'zh-CN': '对象模型',
  },
  'canvas.research.section.roadmap': {
    'en-US': 'From validation to ecosystem',
    'zh-CN': '从验证到生态',
  },
  'canvas.research.section.insights': {
    'en-US': 'Key competitor takeaways',
    'zh-CN': '关键竞品结论',
  },
  'canvas.help.eyebrow': {
    'en-US': 'Keyboard shortcuts',
    'zh-CN': '快捷操作',
  },
  'canvas.help.title': {
    'en-US': 'Stay focused on the canvas',
    'zh-CN': '在画布中保持专注',
  },
  'canvas.help.close': {
    'en-US': 'Close keyboard shortcuts',
    'zh-CN': '关闭快捷操作',
  },
  'canvas.help.shortcut.dismiss': {
    'en-US': 'Close the overlay or clear the selection',
    'zh-CN': '关闭浮层或清除选择',
  },
  'canvas.help.shortcut.delete': {
    'en-US': 'Delete selected objects',
    'zh-CN': '删除选中的对象',
  },
  'canvas.help.shortcut.tools': {
    'en-US': 'Select, pan, or create text',
    'zh-CN': '选择、平移或创建文本',
  },
  'canvas.help.shortcut.zoom': {
    'en-US': 'Fit, 100%, or focus the selection',
    'zh-CN': '适应、100%、聚焦选区',
  },
  'canvas.help.shortcut.focusDock': {
    'en-US': 'Focus the bottom Agent Dock',
    'zh-CN': '聚焦底部 Agent Dock',
  },
  'canvas.help.shortcut.send': {
    'en-US': 'Send a task / insert a line break',
    'zh-CN': '发送任务 / 换行',
  },
  'canvas.generation.mode.text': {
    'en-US': 'Text generation',
    'zh-CN': '文本生成',
  },
  'canvas.generation.mode.image': {
    'en-US': 'Image generation',
    'zh-CN': '图片生成',
  },
  'canvas.generation.mode.video': {
    'en-US': 'Video generation',
    'zh-CN': '视频生成',
  },
  'canvas.generation.status.generated': {
    'en-US': 'Generated',
    'zh-CN': '已生成',
  },
  'canvas.generation.status.draft': {
    'en-US': 'Draft',
    'zh-CN': '草稿',
  },
  'canvas.generation.title.result': {
    'en-US': 'Result',
    'zh-CN': '结果',
  },
  'canvas.generation.title.draft': {
    'en-US': 'Draft',
    'zh-CN': '草稿',
  },
  'canvas.generation.nodeKicker': {
    'en-US': 'Generator node',
    'zh-CN': '生成节点',
  },
  'canvas.generation.expand': {
    'en-US': 'Expand generator workbench width',
    'zh-CN': '展开生成操作台宽度',
  },
  'canvas.generation.collapse': {
    'en-US': 'Collapse generator workbench width',
    'zh-CN': '收起生成操作台宽度',
  },
  'canvas.generation.close': {
    'en-US': 'Close generator workbench',
    'zh-CN': '关闭生成操作台',
  },
  'canvas.generation.capabilities': {
    'en-US': 'Generation capabilities',
    'zh-CN': '生成能力',
  },
  'canvas.generation.references': {
    'en-US': 'Reference assets',
    'zh-CN': '参考素材',
  },
  'canvas.generation.reference.one': {
    'en-US': 'Reference asset one',
    'zh-CN': '参考素材一',
  },
  'canvas.generation.reference.two': {
    'en-US': 'Reference asset two',
    'zh-CN': '参考素材二',
  },
  'canvas.generation.reference.three': {
    'en-US': 'Reference asset three',
    'zh-CN': '参考素材三',
  },
  'canvas.generation.addReference': {
    'en-US': 'Add reference asset',
    'zh-CN': '添加参考素材',
  },
  'canvas.toast.generation.addReference': {
    'en-US': 'The reference asset picker will open in the full product (prototype)',
    'zh-CN': '参考素材选择器将在完整产品中打开（原型模拟）',
  },
  'canvas.generation.promptLabel': {
    'en-US': 'Prompt',
    'zh-CN': '提示词',
  },
  'canvas.generation.placeholder.text': {
    'en-US': 'Describe the text to generate…',
    'zh-CN': '描述要生成的文本…',
  },
  'canvas.generation.placeholder.image': {
    'en-US': 'Describe the image to generate…',
    'zh-CN': '描述要生成的画面…',
  },
  'canvas.generation.placeholder.video': {
    'en-US': 'Describe the shot to generate…',
    'zh-CN': '描述要生成的镜头…',
  },
  'canvas.generation.cost': {
    'en-US': '≈ {{points}} credits',
    'zh-CN': '≈ {{points}} 积分',
  },
  'canvas.generation.submit': {
    'en-US': 'Submit {{label}}',
    'zh-CN': '提交{{label}}',
  },
  'canvas.generation.parameter.model': {
    'en-US': 'Model',
    'zh-CN': '模型',
  },
  'canvas.generation.parameter.length': {
    'en-US': 'Length',
    'zh-CN': '长度',
  },
  'canvas.generation.parameter.tone': {
    'en-US': 'Tone',
    'zh-CN': '语气',
  },
  'canvas.generation.parameter.format': {
    'en-US': 'Format',
    'zh-CN': '格式',
  },
  'canvas.generation.parameter.ratio': {
    'en-US': 'Aspect ratio',
    'zh-CN': '比例',
  },
  'canvas.generation.parameter.count': {
    'en-US': 'Count',
    'zh-CN': '数量',
  },
  'canvas.generation.parameter.style': {
    'en-US': 'Style',
    'zh-CN': '风格',
  },
  'canvas.generation.parameter.spec': {
    'en-US': 'Spec',
    'zh-CN': '规格',
  },
  'canvas.generation.parameter.duration': {
    'en-US': 'Duration',
    'zh-CN': '时长',
  },
  'canvas.generation.parameter.shot': {
    'en-US': 'Shot',
    'zh-CN': '镜头',
  },
  'canvas.generation.capability.freeWriting': {
    'en-US': 'Free writing',
    'zh-CN': '自由写作',
  },
  'canvas.generation.capability.referenceRewrite': {
    'en-US': 'Reference rewrite',
    'zh-CN': '参考改写',
  },
  'canvas.generation.capability.structure': {
    'en-US': 'Structure extraction',
    'zh-CN': '结构提炼',
  },
  'canvas.generation.capability.continue': {
    'en-US': 'Continue writing',
    'zh-CN': '继续扩写',
  },
  'canvas.generation.capability.textToImage': {
    'en-US': 'Text to image',
    'zh-CN': '文生图',
  },
  'canvas.generation.capability.multiReference': {
    'en-US': 'Multi-image reference',
    'zh-CN': '多图参考',
  },
  'canvas.generation.capability.styleTransfer': {
    'en-US': 'Style transfer',
    'zh-CN': '风格迁移',
  },
  'canvas.generation.capability.inpaint': {
    'en-US': 'Inpainting',
    'zh-CN': '局部重绘',
  },
  'canvas.generation.capability.firstLastFrame': {
    'en-US': 'First/last frame',
    'zh-CN': '首尾帧',
  },
  'canvas.generation.capability.motionTransfer': {
    'en-US': 'Motion imitation',
    'zh-CN': '动作模仿',
  },
  'canvas.generation.capability.anyReference': {
    'en-US': 'All-purpose reference',
    'zh-CN': '全能参考',
  },
  'canvas.generation.capability.videoEdit': {
    'en-US': 'Video editing',
    'zh-CN': '视频编辑',
  },
  'canvas.generation.model.claudeSonnet': {
    'en-US': 'Claude · Sonnet',
    'zh-CN': 'Claude · Sonnet',
  },
  'canvas.generation.model.gpt41': {
    'en-US': 'GPT · 4.1',
    'zh-CN': 'GPT · 4.1',
  },
  'canvas.generation.model.geminiPro': {
    'en-US': 'Gemini · Pro',
    'zh-CN': 'Gemini · Pro',
  },
  'canvas.generation.model.fluxPro': {
    'en-US': 'Flux · Pro',
    'zh-CN': 'Flux · Pro',
  },
  'canvas.generation.model.sdxlTurbo': {
    'en-US': 'SDXL · Turbo',
    'zh-CN': 'SDXL · Turbo',
  },
  'canvas.generation.model.ideogramV3': {
    'en-US': 'Ideogram · V3',
    'zh-CN': 'Ideogram · V3',
  },
  'canvas.generation.model.kling16': {
    'en-US': 'Kling · 1.6',
    'zh-CN': 'Kling · 1.6',
  },
  'canvas.generation.model.runwayGen3': {
    'en-US': 'Runway · Gen-3',
    'zh-CN': 'Runway · Gen-3',
  },
  'canvas.generation.model.lumaRay2': {
    'en-US': 'Luma · Ray 2',
    'zh-CN': 'Luma · Ray 2',
  },
  'canvas.generation.option.concise': {
    'en-US': 'Concise',
    'zh-CN': '精炼',
  },
  'canvas.generation.option.standard': {
    'en-US': 'Standard',
    'zh-CN': '标准',
  },
  'canvas.generation.option.detailed': {
    'en-US': 'Detailed',
    'zh-CN': '详细',
  },
  'canvas.generation.option.professional': {
    'en-US': 'Professional',
    'zh-CN': '专业',
  },
  'canvas.generation.option.narrative': {
    'en-US': 'Narrative',
    'zh-CN': '叙事',
  },
  'canvas.generation.option.direct': {
    'en-US': 'Direct',
    'zh-CN': '直接',
  },
  'canvas.generation.option.paragraph': {
    'en-US': 'Paragraph',
    'zh-CN': '段落',
  },
  'canvas.generation.option.bullets': {
    'en-US': 'Bullet points',
    'zh-CN': '要点',
  },
  'canvas.generation.option.outline': {
    'en-US': 'Outline',
    'zh-CN': '大纲',
  },
  'canvas.generation.option.ratio11': {
    'en-US': '1:1',
    'zh-CN': '1:1',
  },
  'canvas.generation.option.ratio43': {
    'en-US': '4:3',
    'zh-CN': '4:3',
  },
  'canvas.generation.option.ratio169': {
    'en-US': '16:9',
    'zh-CN': '16:9',
  },
  'canvas.generation.option.spec169': {
    'en-US': '16:9 · 1080p',
    'zh-CN': '16:9 · 1080p',
  },
  'canvas.generation.option.spec916': {
    'en-US': '9:16 · 1080p',
    'zh-CN': '9:16 · 1080p',
  },
  'canvas.generation.option.spec11': {
    'en-US': '1:1 · 720p',
    'zh-CN': '1:1 · 720p',
  },
  'canvas.generation.option.fourImages': {
    'en-US': '4 images',
    'zh-CN': '4 张',
  },
  'canvas.generation.option.twoImages': {
    'en-US': '2 images',
    'zh-CN': '2 张',
  },
  'canvas.generation.option.oneImage': {
    'en-US': '1 image',
    'zh-CN': '1 张',
  },
  'canvas.generation.option.cinematic': {
    'en-US': 'Cinematic',
    'zh-CN': '电影感',
  },
  'canvas.generation.option.editorial': {
    'en-US': 'Editorial',
    'zh-CN': '编辑感',
  },
  'canvas.generation.option.minimal': {
    'en-US': 'Minimal',
    'zh-CN': '极简',
  },
  'canvas.generation.option.sixSeconds': {
    'en-US': '6 seconds',
    'zh-CN': '6 秒',
  },
  'canvas.generation.option.fourSeconds': {
    'en-US': '4 seconds',
    'zh-CN': '4 秒',
  },
  'canvas.generation.option.tenSeconds': {
    'en-US': '10 seconds',
    'zh-CN': '10 秒',
  },
  'canvas.generation.option.slowPush': {
    'en-US': 'Slow push-in',
    'zh-CN': '缓慢推镜',
  },
  'canvas.generation.option.static': {
    'en-US': 'Static shot',
    'zh-CN': '固定镜头',
  },
  'canvas.generation.option.horizontal': {
    'en-US': 'Horizontal move',
    'zh-CN': '横向移动',
  },
  'canvas.add.text': {
    'en-US': 'Text generation',
    'zh-CN': '文本生成',
  },
  'canvas.add.image': {
    'en-US': 'Image generation',
    'zh-CN': '图片生成',
  },
  'canvas.add.video': {
    'en-US': 'Video generation',
    'zh-CN': '视频生成',
  },
  'canvas.add.file': {
    'en-US': 'File',
    'zh-CN': '文件',
  },
  'canvas.add.frame': {
    'en-US': 'Frame',
    'zh-CN': 'Frame',
  },
  'canvas.add.ariaLabel': {
    'en-US': 'Add content',
    'zh-CN': '添加内容',
  },
  'canvas.toast.add.file': {
    'en-US': 'File import will open in the full product (prototype)',
    'zh-CN': '文件导入将在完整产品中打开（原型模拟）',
  },
  'canvas.toast.add.frame': {
    'en-US': 'Frame creation will be available in the full product (prototype)',
    'zh-CN': 'Frame 创建将在完整产品中提供（原型模拟）',
  },
  'canvas.agent.thread.ariaLabel': {
    'en-US': 'Agent messages and run status',
    'zh-CN': 'Agent 消息与运行状态',
  },
  'canvas.agent.context.ariaLabel': {
    'en-US': 'Agent context',
    'zh-CN': 'Agent 上下文',
  },
  'canvas.agent.context.selection': {
    'en-US': 'Current selection',
    'zh-CN': '当前选区',
  },
  'canvas.agent.context.whole': {
    'en-US': 'Whole canvas',
    'zh-CN': '整张画布',
  },
  'canvas.agent.reset': {
    'en-US': 'Reset',
    'zh-CN': '重置',
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
    'en-US': 'Tell Agent what to do next…',
    'zh-CN': '告诉 Agent 下一步要完成什么…',
  },
  'canvas.agent.send': {
    'en-US': 'Send to Agent',
    'zh-CN': '发送给 Agent',
  },
  'canvas.agent.run.label': {
    'en-US': 'Agent Run',
    'zh-CN': 'Agent Run',
  },
  'canvas.agent.run.status.running': {
    'en-US': 'Running',
    'zh-CN': '运行中',
  },
  'canvas.agent.run.status.paused': {
    'en-US': 'Paused',
    'zh-CN': '已暂停',
  },
  'canvas.agent.run.status.completed': {
    'en-US': 'Completed',
    'zh-CN': '已完成',
  },
  'canvas.agent.run.status.withProgress': {
    'en-US': '{{status}} · {{progress}}/{{total}}',
    'zh-CN': '{{status}} · {{progress}}/{{total}}',
  },
  'canvas.agent.run.control.pause': {
    'en-US': 'Pause',
    'zh-CN': '暂停',
  },
  'canvas.agent.run.control.resume': {
    'en-US': 'Resume',
    'zh-CN': '继续',
  },
  'canvas.agent.run.control.retry': {
    'en-US': 'Retry',
    'zh-CN': '重试',
  },
  'canvas.agent.run.progress': {
    'en-US': 'Agent run progress',
    'zh-CN': 'Agent 运行进度',
  },
  'canvas.agent.run.step.locateGoals': {
    'en-US': 'Extract positioning and target users',
    'zh-CN': '提取定位与目标用户',
  },
  'canvas.agent.run.step.interaction': {
    'en-US': 'Summarize interactions and objects',
    'zh-CN': '归纳交互与对象',
  },
  'canvas.agent.run.step.matrix': {
    'en-US': 'Generate capability matrix',
    'zh-CN': '生成能力矩阵',
  },
  'canvas.agent.run.step.mvp': {
    'en-US': 'Form an MVP page direction',
    'zh-CN': '形成 MVP 页面方向',
  },
  'canvas.node.webReference': {
    'en-US': 'Web reference',
    'zh-CN': 'Web 引用',
  },
  'canvas.node.imageReference': {
    'en-US': 'Image reference',
    'zh-CN': '图片引用',
  },
  'canvas.node.file': {
    'en-US': 'File',
    'zh-CN': '文件',
  },
  'canvas.node.text': {
    'en-US': 'Text',
    'zh-CN': '文本',
  },
  'canvas.node.structuredResult': {
    'en-US': 'Structured result',
    'zh-CN': '结构化结果',
  },
  'canvas.node.result': {
    'en-US': 'Result',
    'zh-CN': 'Result',
  },
  'canvas.node.resultNew': {
    'en-US': 'Result New',
    'zh-CN': 'Result 新',
  },
  'canvas.node.editableResult': {
    'en-US': 'Editable result',
    'zh-CN': '可编辑结果',
  },
  'canvas.node.openWorkbench': {
    'en-US': '{{title}}, open generator workbench',
    'zh-CN': '{{title}}，打开生成操作台',
  },
  'canvas.matrix.capability': {
    'en-US': 'Capability',
    'zh-CN': '能力',
  },
  'canvas.matrix.reference': {
    'en-US': 'Reference',
    'zh-CN': '参考',
  },
  'canvas.matrix.target': {
    'en-US': 'Target',
    'zh-CN': '目标',
  },
  'canvas.matrix.wholeCanvas': {
    'en-US': 'Whole-canvas context',
    'zh-CN': '整图上下文',
  },
  'canvas.matrix.visibleProcess': {
    'en-US': 'Visible process',
    'zh-CN': '过程可见',
  },
  'canvas.matrix.resultPlacement': {
    'en-US': 'Result placement',
    'zh-CN': '结果落位',
  },
  'canvas.toast.templateSelected': {
    'en-US': 'Selected “{{template}}” template',
    'zh-CN': '已选择「{{template}}」模板',
  },
  'canvas.toast.tool.hand': {
    'en-US': 'Hand tool: drag empty space to pan the canvas',
    'zh-CN': '手形工具：拖动空白区域平移画布',
  },
  'canvas.toast.tool.select': {
    'en-US': 'Select tool: drag empty space to select objects',
    'zh-CN': '选择工具：拖动空白区域框选对象',
  },
  'canvas.toast.selection.deleted': {
    'en-US': 'Deleted {{count}} objects',
    'zh-CN': '已删除 {{count}} 个对象',
  },
  'canvas.toast.generation.promptRequired': {
    'en-US': 'Describe what to generate first',
    'zh-CN': '请先描述要生成的内容',
  },
  'canvas.toast.generation.created': {
    'en-US': 'Created {{label}} node',
    'zh-CN': '已创建{{label}}节点',
  },
  'canvas.toast.generation.completedMessage': {
    'en-US': '{{label}} finished; the generator node was updated in place.',
    'zh-CN': '{{label}}完成，已原地更新生成节点。',
  },
  'canvas.toast.generation.completed': {
    'en-US': '{{label}} finished; the generator node was updated in place',
    'zh-CN': '{{label}}完成，生成节点已原地更新',
  },
  'canvas.toast.text.created': {
    'en-US': 'Created a text object at the center of the current viewport',
    'zh-CN': '已在当前视口中心创建文本对象',
  },
  'canvas.toast.agent.promptRequired': {
    'en-US': 'Enter a task for Agent',
    'zh-CN': '请输入要交给 Agent 的任务',
  },
  'canvas.toast.agent.runMissing': {
    'en-US': 'Agent Run was deleted; reset the demo before starting a task',
    'zh-CN': 'Agent Run 已被删除；请重置演示后再发起任务',
  },
  'canvas.toast.agent.busy': {
    'en-US': 'The current task is {{status}}. Finish, resume, or retry it before starting a new task',
    'zh-CN': '当前任务{{status}}，请完成、继续或重试后再发起新任务',
  },
  'canvas.toast.demo.reset': {
    'en-US': 'Demo and Agent messages reset',
    'zh-CN': '演示和 Agent 消息已重置',
  },
  'canvas.toast.idea.required': {
    'en-US': 'Describe the work you want to complete first',
    'zh-CN': '请先描述想完成的工作',
  },
  'canvas.toast.idea.created': {
    'en-US': 'Created a “{{template}}” canvas from the goal',
    'zh-CN': '已根据目标创建「{{template}}」画布',
  },
  'canvas.context.whole': {
    'en-US': 'Objects across the whole canvas will be included as input.',
    'zh-CN': '整张画布中的对象将作为本次输入。',
  },
  'canvas.context.selection': {
    'en-US': '{{count}} selected objects will be included as input, with source relationships preserved.',
    'zh-CN': '{{count}} 个选中对象将作为本次输入，并保留来源关系。',
  },
  'canvas.context.default': {
    'en-US': 'Web pages, screenshots, and research materials will be included as input.',
    'zh-CN': '网页、截图和研究资料将作为本次输入。',
  },
} satisfies LocaleCatalog
