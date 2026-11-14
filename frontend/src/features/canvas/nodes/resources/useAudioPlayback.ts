import {
  useEffect,
  useRef,
  useState,
  type ChangeEvent,
} from 'react'

/** 把毫秒时长换算成秒；无效或缺失时为 null。 */
export function finiteSeconds(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value / 1000 : null
}

interface AudioPlaybackInput {
  /** 已签名的原文件 URL；null 表示还没请求到。 */
  originalUrl: string | null
  /** 触发原文件签名（首次请求或刷新）。 */
  requestOriginal: () => void
  /** 资源自带时长（秒），在 <audio> metadata 到达前先展示。 */
  initialDuration: number
}

/**
 * 音频播放器的媒体元素状态机：播放/暂停/拖动/音量/静音，以及原文件 URL 到位后
 * 继续用户先前点击但尚未有源的播放请求。
 */
export function useAudioPlayback({ originalUrl, requestOriginal, initialDuration }: AudioPlaybackInput) {
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const pendingPlayRef = useRef(false)
  const [playing, setPlaying] = useState(false)
  const [currentTime, setCurrentTime] = useState(0)
  const [duration, setDuration] = useState(initialDuration)
  const [volume, setVolume] = useState(1)
  const [muted, setMuted] = useState(false)
  const [playError, setPlayError] = useState(false)

  useEffect(() => {
    if (!originalUrl || !audioRef.current || !pendingPlayRef.current) {
      return
    }
    pendingPlayRef.current = false
    setPlayError(false)
    const audio = audioRef.current
    const result = audio.play()
    if (result && typeof result.then === 'function') {
      result
        .then(() => setPlaying(true))
        .catch(() => {
          setPlaying(false)
          setPlayError(true)
        })
    } else {
      setPlaying(true)
    }
  }, [originalUrl])

  const togglePlay = () => {
    const audio = audioRef.current
    if (!audio || !originalUrl) {
      pendingPlayRef.current = true
      setPlayError(false)
      requestOriginal()
      return
    }
    setPlayError(false)
    if (audio.paused) {
      const result = audio.play()
      if (result && typeof result.then === 'function') {
        result
          .then(() => setPlaying(true))
          .catch(() => {
            setPlaying(false)
            setPlayError(true)
          })
      } else {
        setPlaying(true)
      }
    } else {
      audio.pause()
      setPlaying(false)
    }
  }

  const handleSeek = (event: ChangeEvent<HTMLInputElement>) => {
    const next = Number(event.target.value)
    setCurrentTime(next)
    const audio = audioRef.current
    if (audio && Number.isFinite(audio.duration)) {
      audio.currentTime = Math.min(next, audio.duration)
    }
  }

  const handleVolume = (event: ChangeEvent<HTMLInputElement>) => {
    const next = Number(event.target.value)
    setVolume(next)
    setMuted(false)
    const audio = audioRef.current
    if (audio) {
      audio.volume = next
      audio.muted = false
    }
  }

  const toggleMute = () => {
    const next = !muted
    setMuted(next)
    if (audioRef.current) {
      audioRef.current.muted = next
    }
  }

  /** <audio> 的 ref 回调：只在挂载时记录元素，卸载时保留引用以便清理。 */
  const attachAudio = (element: HTMLAudioElement | null) => {
    if (element) {
      audioRef.current = element
    }
  }

  const handleLoadedMetadata = (event: { currentTarget: HTMLAudioElement }) => {
    const audio = event.currentTarget
    if (Number.isFinite(audio.duration)) {
      setDuration(audio.duration)
    }
    setCurrentTime(audio.currentTime)
  }

  return {
    playing,
    currentTime,
    duration,
    volume,
    muted,
    playError,
    togglePlay,
    handleSeek,
    handleVolume,
    toggleMute,
    attachAudio,
    handleLoadedMetadata,
    handleTimeUpdate: (event: { currentTarget: HTMLAudioElement }) => setCurrentTime(event.currentTarget.currentTime),
    handleEnded: () => setPlaying(false),
    handlePause: () => setPlaying(false),
    handlePlay: () => setPlaying(true),
  }
}
