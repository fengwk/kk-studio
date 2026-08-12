import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach } from 'vitest'
import { setLocale } from '@/shared/i18n'

beforeEach(() => {
  setLocale('zh-CN')
})

class ResizeObserverStub {
  private readonly callback: ResizeObserverCallback

  constructor(callback: ResizeObserverCallback) {
    this.callback = callback
  }

  observe(target: Element) {
    // React Flow 在 jsdom 中需要立即触发的测量回调。
    this.callback(
      [{
        target,
        contentRect: {
          x: 0,
          y: 0,
          width: 1200,
          height: 800,
          top: 0,
          left: 0,
          bottom: 800,
          right: 1200,
          toJSON() {
            return this
          },
        },
        borderBoxSize: [],
        contentBoxSize: [],
        devicePixelContentBoxSize: [],
      } as ResizeObserverEntry],
      this,
    )
  }

  unobserve() {}
  disconnect() {}
}

globalThis.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver

// jsdom 不实现本地 blob URL；附件 strip 的预览需要稳定 stub。
if (typeof URL !== 'undefined' && typeof URL.createObjectURL !== 'function') {
  URL.createObjectURL = () => 'blob:kk-studio-test'
  URL.revokeObjectURL = () => undefined
}

// Thread 状态压缩通过 2D canvas 上下文测量文本。
if (typeof HTMLCanvasElement !== 'undefined') {
  Object.defineProperty(HTMLCanvasElement.prototype, 'getContext', {
    configurable: true,
    value(contextId: string) {
      if (contextId !== '2d') {
        return null
      }
      return {
        font: '',
        measureText(text: string) {
          return { width: text.length * 7 }
        },
      } as unknown as CanvasRenderingContext2D
    },
  })
}

// React Flow 的测量路径会在 jsdom 中构造 DOMMatrixReadOnly。
if (typeof globalThis.DOMMatrixReadOnly === 'undefined') {
  class DOMMatrixReadOnlyStub {
    a = 1
    b = 0
    c = 0
    d = 1
    e = 0
    f = 0
    m11 = 1
    m12 = 0
    m13 = 0
    m14 = 0
    m21 = 0
    m22 = 1
    m23 = 0
    m24 = 0
    m31 = 0
    m32 = 0
    m33 = 1
    m34 = 0
    m41 = 0
    m42 = 0
    m43 = 0
    m44 = 1
    is2D = true
    isIdentity = true

    constructor(_init?: string | number[]) {}

    inverse() {
      return new DOMMatrixReadOnlyStub()
    }

    multiply() {
      return new DOMMatrixReadOnlyStub()
    }

    translate() {
      return new DOMMatrixReadOnlyStub()
    }

    scale() {
      return new DOMMatrixReadOnlyStub()
    }

    transformPoint(point?: { x?: number; y?: number; z?: number; w?: number }) {
      return {
        x: point?.x ?? 0,
        y: point?.y ?? 0,
        z: point?.z ?? 0,
        w: point?.w ?? 1,
      }
    }
  }
  globalThis.DOMMatrixReadOnly = DOMMatrixReadOnlyStub as unknown as typeof DOMMatrixReadOnly
  // 一些 RF 代码路径也会访问 DOMMatrix。
  if (typeof globalThis.DOMMatrix === 'undefined') {
    globalThis.DOMMatrix = DOMMatrixReadOnlyStub as unknown as typeof DOMMatrix
  }
}

// 为 React Flow 提供布局几何，使节点离开 visibility:hidden 且边能够渲染。
const geometry = {
  x: 0,
  y: 0,
  width: 1200,
  height: 800,
  top: 0,
  left: 0,
  bottom: 800,
  right: 1200,
  toJSON() {
    return this
  },
} as DOMRect

if (typeof HTMLElement !== 'undefined') {
  Object.defineProperty(HTMLElement.prototype, 'getBoundingClientRect', {
    configurable: true,
    value() {
      return geometry
    },
  })
  Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
    configurable: true,
    get() {
      return 1200
    },
  })
  Object.defineProperty(HTMLElement.prototype, 'clientHeight', {
    configurable: true,
    get() {
      return 800
    },
  })
  Object.defineProperty(HTMLElement.prototype, 'offsetWidth', {
    configurable: true,
    get() {
      return 1200
    },
  })
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
    configurable: true,
    get() {
      return 800
    },
  })
}

if (typeof Element !== 'undefined' && !('inert' in Element.prototype)) {
  Object.defineProperty(Element.prototype, 'inert', {
    configurable: true,
    enumerable: true,
    get(this: Element) {
      return this.hasAttribute('inert')
    },
    set(this: Element, value: boolean) {
      if (value) {
        this.setAttribute('inert', '')
      } else {
        this.removeAttribute('inert')
      }
    },
  })
}

// jsdom 缺少 React Flow 使用的 SVG 几何辅助；仅在 SVGElement 存在时打补丁。
if (typeof SVGElement !== 'undefined') {
  const svgProto = SVGElement.prototype as SVGElement & {
    getBBox?: () => DOMRect
    getScreenCTM?: () => DOMMatrix | null
  }
  if (typeof svgProto.getBBox !== 'function') {
    svgProto.getBBox = function getBBox() {
      return {
        x: 0,
        y: 0,
        width: 0,
        height: 0,
        top: 0,
        right: 0,
        bottom: 0,
        left: 0,
        toJSON() {
          return this
        },
      } as DOMRect
    }
  }
  if (typeof svgProto.getScreenCTM !== 'function') {
    svgProto.getScreenCTM = function getScreenCTM() {
      return null
    }
  }
}

if (typeof HTMLElement !== 'undefined' && typeof HTMLElement.prototype.scrollIntoView !== 'function') {
  HTMLElement.prototype.scrollIntoView = function scrollIntoView() {}
}

// 为 jsdom 提供最小的 HTMLDialogElement polyfill，使 Help 可以使用真正的 showModal()/close()。
if (typeof HTMLDialogElement !== 'undefined') {
  const proto = HTMLDialogElement.prototype as HTMLDialogElement & {
    showModal?: () => void
    close?: () => void
  }
  if (typeof proto.showModal !== 'function') {
    proto.showModal = function showModal(this: HTMLDialogElement) {
      this.setAttribute('open', '')
      this.setAttribute('data-modal', 'true')
    }
  }
  if (typeof proto.close !== 'function') {
    proto.close = function close(this: HTMLDialogElement) {
      this.removeAttribute('open')
      this.removeAttribute('data-modal')
    }
  }
  // open 属性在 jsdom 中可能是只读的属性支撑属性；确保 modal 的 open 状态可被检测。
  try {
    Object.defineProperty(HTMLDialogElement.prototype, 'open', {
      configurable: true,
      get(this: HTMLDialogElement) {
        return this.hasAttribute('open')
      },
      set(this: HTMLDialogElement, value: boolean) {
        if (value) {
          this.setAttribute('open', '')
        } else {
          this.removeAttribute('open')
        }
      },
    })
  } catch {
    // 若环境已定义 open 则忽略。
  }
}

afterEach(() => {
  cleanup()
})
