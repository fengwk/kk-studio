declare module 'picomatch' {
  interface PicomatchOptions {
    dot?: boolean
    nobrace?: boolean
    noglobstar?: boolean
    bash?: boolean
    nonegate?: boolean
    noext?: boolean
    nocase?: boolean
    capture?: boolean
  }

  type Matcher = (test: string) => boolean

  interface Picomatch {
    (glob: string, options?: PicomatchOptions): Matcher
    isMatch(test: string, glob: string, options?: PicomatchOptions): boolean
  }

  const picomatch: Picomatch
  export = picomatch
}
