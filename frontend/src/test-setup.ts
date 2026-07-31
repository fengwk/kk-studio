import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

class ResizeObserverStub {
  private readonly callback: ResizeObserverCallback

  constructor(callback: ResizeObserverCallback) {
    this.callback = callback
  }

  observe(target: Element) {
    // React Flow needs an immediate measurement callback in jsdom.
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

// Thread status packing measures text through a 2D canvas context.
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

// React Flow measurement path constructs DOMMatrixReadOnly in jsdom.
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
  // Some RF code paths also touch DOMMatrix.
  if (typeof globalThis.DOMMatrix === 'undefined') {
    globalThis.DOMMatrix = DOMMatrixReadOnlyStub as unknown as typeof DOMMatrix
  }
}

// Give layout geometry to React Flow so nodes leave visibility:hidden and edges can render.
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

// jsdom lacks SVG geometry helpers used by React Flow; patch only when SVGElement exists.
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

// Minimal HTMLDialogElement polyfill for jsdom so Help can use real showModal()/close().
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
  // open property may be read-only attribute-backed in jsdom; ensure modal open is detectable.
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
    // ignore if environment already defines open
  }
}

afterEach(() => {
  cleanup()
})
