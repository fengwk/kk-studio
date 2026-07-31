import type {
  CanvasLink,
  CanvasNode,
  CanvasViewport,
  GenerationMode,
  GenerationProfile,
} from '@/features/canvas/types'

export const MIN_ZOOM = 0.25
export const MAX_ZOOM = 1.45
export const DEFAULT_VIEWPORT: CanvasViewport = { x: 80, y: 20, scale: 0.6 }
export const SAVE_SETTLE_MS = 650

export const RUN_STEPS = [
  'canvas.agent.run.step.locateGoals',
  'canvas.agent.run.step.interaction',
  'canvas.agent.run.step.matrix',
  'canvas.agent.run.step.mvp',
] as const

export const DEFAULT_CONTEXT_IDS = ['web', 'image', 'file'] as const

export const GENERATION_PROFILES: Record<GenerationMode, GenerationProfile> = {
  text: {
    labelKey: 'canvas.generation.mode.text',
    icon: 'T',
    prompt: '将画布中的竞品观察整理为一段清晰、有判断力的产品定位说明。',
    cost: 3,
    size: { width: 300, height: 196 },
    capabilities: [
      'canvas.generation.capability.freeWriting',
      'canvas.generation.capability.referenceRewrite',
      'canvas.generation.capability.structure',
      'canvas.generation.capability.continue',
    ],
    groups: [
      {
        key: 'canvas.generation.parameter.model',
        values: [
          'canvas.generation.model.claudeSonnet',
          'canvas.generation.model.gpt41',
          'canvas.generation.model.geminiPro',
        ],
      },
      {
        key: 'canvas.generation.parameter.length',
        values: [
          'canvas.generation.option.concise',
          'canvas.generation.option.standard',
          'canvas.generation.option.detailed',
        ],
      },
      {
        key: 'canvas.generation.parameter.tone',
        values: [
          'canvas.generation.option.professional',
          'canvas.generation.option.narrative',
          'canvas.generation.option.direct',
        ],
      },
      {
        key: 'canvas.generation.parameter.format',
        values: [
          'canvas.generation.option.paragraph',
          'canvas.generation.option.bullets',
          'canvas.generation.option.outline',
        ],
      },
    ],
  },
  image: {
    labelKey: 'canvas.generation.mode.image',
    icon: '◒',
    prompt: '低饱和黑白产品视觉，留出清晰的编辑空间。',
    cost: 8,
    size: { width: 240, height: 224 },
    capabilities: [
      'canvas.generation.capability.textToImage',
      'canvas.generation.capability.multiReference',
      'canvas.generation.capability.styleTransfer',
      'canvas.generation.capability.inpaint',
    ],
    groups: [
      {
        key: 'canvas.generation.parameter.model',
        values: [
          'canvas.generation.model.fluxPro',
          'canvas.generation.model.sdxlTurbo',
          'canvas.generation.model.ideogramV3',
        ],
      },
      {
        key: 'canvas.generation.parameter.ratio',
        values: ['canvas.generation.option.ratio11', 'canvas.generation.option.ratio43', 'canvas.generation.option.ratio169'],
      },
      {
        key: 'canvas.generation.parameter.count',
        values: [
          'canvas.generation.option.fourImages',
          'canvas.generation.option.twoImages',
          'canvas.generation.option.oneImage',
        ],
      },
      {
        key: 'canvas.generation.parameter.style',
        values: [
          'canvas.generation.option.cinematic',
          'canvas.generation.option.editorial',
          'canvas.generation.option.minimal',
        ],
      },
    ],
  },
  video: {
    labelKey: 'canvas.generation.mode.video',
    icon: '▻',
    prompt: '黑灰创作工作台缓慢推镜，抽象素材在画布中展开，保持克制的镜头运动。',
    cost: 28,
    size: { width: 286, height: 214 },
    capabilities: [
      'canvas.generation.capability.firstLastFrame',
      'canvas.generation.capability.multiReference',
      'canvas.generation.capability.motionTransfer',
      'canvas.generation.capability.anyReference',
      'canvas.generation.capability.videoEdit',
    ],
    groups: [
      {
        key: 'canvas.generation.parameter.model',
        values: [
          'canvas.generation.model.kling16',
          'canvas.generation.model.runwayGen3',
          'canvas.generation.model.lumaRay2',
        ],
      },
      {
        key: 'canvas.generation.parameter.spec',
        values: [
          'canvas.generation.option.spec169',
          'canvas.generation.option.spec916',
          'canvas.generation.option.spec11',
        ],
      },
      {
        key: 'canvas.generation.parameter.duration',
        values: [
          'canvas.generation.option.sixSeconds',
          'canvas.generation.option.fourSeconds',
          'canvas.generation.option.tenSeconds',
        ],
      },
      {
        key: 'canvas.generation.parameter.shot',
        values: [
          'canvas.generation.option.slowPush',
          'canvas.generation.option.static',
          'canvas.generation.option.horizontal',
        ],
      },
    ],
  },
}

export const ADD_MENU_ITEMS: Array<{
  action: 'text' | 'image' | 'video' | 'file' | 'frame'
  labelKey: string
  icon: string
}> = [
  { action: 'text', labelKey: 'canvas.add.text', icon: 'T' },
  { action: 'image', labelKey: 'canvas.add.image', icon: '◒' },
  { action: 'video', labelKey: 'canvas.add.video', icon: '▻' },
  { action: 'file', labelKey: 'canvas.add.file', icon: '▤' },
  { action: 'frame', labelKey: 'canvas.add.frame', icon: '□' },
]

export const RESEARCH_CONTENT: Record<'research' | 'architecture' | 'roadmap', Array<{ title: string; body: string; phase?: string }>> = {
  research: [
    { title: 'NeoWOW', body: '首页想法输入、模板分类、个人/协作画布与 Skill 闭环值得借鉴；产品底层仍须保持通用，而非绑定垂直领域。' },
    { title: 'WorkRally · Seko', body: 'WorkRally 证明 Agent/CLI 可操作带状态的画布对象；Seko 证明“灵感 → 自动策划”有效。一句话创建应先展示计划，而不是黑盒一键完成。' },
    { title: 'Miro AI · tldraw Computer', body: '整张画布可以作为 Prompt，Agent 可以在空间中构建结构。执行过程应该成为可见对象，但普通用户不应手工搭建工作流。' },
    { title: '本地 infinite-canvas', body: 'CSS 视口变换、节点/存储分层、导入导出是有效参考；避免反常框选、永久大 Dock、业务字段堆积和大页面耦合。' },
  ],
  architecture: [
    { title: 'Agent 原生的多模态创作工作区', body: '资料、想法和产物存在同一空间。输入 → Agent Run → 可编辑结果，过程可见、来源可追溯。' },
    { title: 'React Flow 初步选型', body: 'MIT 许可，适合富 DOM 节点和 Agent 状态；内置拖拽、视口、多选、MiniMap、Controls。它只承担交互渲染，领域模型保持独立。' },
    { title: 'AGPL 风险', body: '本地 infinite-canvas 为 AGPL-3.0。仅作为行为和模块边界参考，不直接复制源码。' },
  ],
  roadmap: [
    { title: '交互原型', body: '中性暗色 Stage、底部 Agent Dock、生成节点和上下文操作台，验证产品表达。', phase: 'A' },
    { title: '画布 MVP', body: '持久化、统一命令历史、素材引用、自动保存和节点/动作注册表。', phase: 'B' },
    { title: 'Agent 原生能力', body: '选区/整图上下文、SSE 状态、画布命令、暂停重试与来源追踪。', phase: 'C' },
    { title: '协作与生态', body: '实时协作、只读分享、创作回放、Playbook / Skill 市场与用量策略。', phase: 'D' },
  ],
}

export function createInitialNodes(): CanvasNode[] {
  return [
    {
      id: 'frame',
      type: 'frame',
      domainKind: 'GROUP',
      x: 70,
      y: 110,
      width: 610,
      height: 430,
      title: '资料 Frame · 输入',
      subtitle: '3 个外部资料 + 研究笔记',
    },
    {
      id: 'web',
      type: 'web',
      domainKind: 'RESOURCE',
      x: 110,
      y: 175,
      width: 158,
      height: 162,
      title: 'NeoWOW 画布与工作流',
      copy: '一句目标、模板和作品闭环',
      meta: '网页 · 3 分钟前',
    },
    {
      id: 'image',
      type: 'image',
      domainKind: 'RESOURCE',
      x: 291,
      y: 175,
      width: 158,
      height: 162,
      title: '竞品编辑器截图',
      copy: '空间组织与低视觉重量',
      meta: '图片 · 2.4 MB',
    },
    {
      id: 'file',
      type: 'file',
      domainKind: 'RESOURCE',
      x: 472,
      y: 175,
      width: 158,
      height: 162,
      title: 'Miro AI / Seko / tldraw 摘录',
      copy: '整图上下文、Agent 执行与画布结果',
      meta: 'PDF · 12 页',
    },
    {
      id: 'note',
      type: 'text',
      domainKind: 'RESOURCE',
      x: 110,
      y: 365,
      width: 310,
      height: 128,
      title: '研究问题',
      copy: '如何让 Agent 理解画布上下文，并把可编辑结果稳定地放回来源附近？',
      meta: '文本 · 已同步',
    },
    {
      id: 'run',
      type: 'run',
      domainKind: 'FUNCTION',
      x: 760,
      y: 290,
      width: 275,
      height: 235,
      title: '竞品研究与归纳',
      status: 'succeeded',
      progress: 4,
      total: 4,
    },
    {
      id: 'matrix',
      type: 'matrix',
      domainKind: 'RESOURCE',
      x: 1145,
      y: 145,
      width: 286,
      height: 192,
      title: '竞品能力矩阵',
      copy: '定位、上下文与执行过程对比',
      meta: '结构化结果',
    },
    {
      id: 'result-a',
      type: 'result',
      domainKind: 'RESOURCE',
      x: 1145,
      y: 405,
      width: 178,
      height: 174,
      title: '方案 A · 研究画布',
      copy: '输入 → 运行 → 可编辑结论',
      variant: 'A',
    },
    {
      id: 'result-b',
      type: 'result',
      domainKind: 'RESOURCE',
      x: 1350,
      y: 405,
      width: 178,
      height: 174,
      title: '方案 B · 创作空间',
      copy: '对象驱动的连续生成',
      variant: 'B',
    },
    {
      id: 'direction',
      type: 'text',
      domainKind: 'RESOURCE',
      x: 1550,
      y: 200,
      width: 260,
      height: 145,
      title: '产品定位',
      copy: 'Agent 原生的多模态创作工作区。过程可见，结果可编辑、可追溯。',
      meta: '最终结论',
    },
  ]
}

export function createInitialLinks(): CanvasLink[] {
  const pairs: Array<[string, string]> = [
    ['web', 'run'],
    ['image', 'run'],
    ['file', 'run'],
    ['note', 'run'],
    ['run', 'matrix'],
    ['run', 'result-a'],
    ['run', 'result-b'],
    ['matrix', 'direction'],
  ]
  return pairs.map(([source, target]) => ({
    id: `${source}->${target}`,
    source,
    target,
    role: 'visibility' as const,
  }))
}
