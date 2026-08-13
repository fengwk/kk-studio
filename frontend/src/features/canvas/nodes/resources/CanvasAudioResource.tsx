import {
  LoaderCircle,
  Pause,
  Play,
  Volume2,
  VolumeX,
} from 'lucide-react'
import {
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type ChangeEvent,
} from 'react'
import type { Resource } from '@/features/canvas/domain'
import { useCanvasResourceUrl } from '@/features/canvas/useCanvasResourceUrl'
import { useI18n } from '@/shared/i18n'

export function CanvasAudioResource({ resource }: { resource: Resource }) {
  return (
    <div className="canvas-resource-media audio">
      <CanvasAudioPlayer resource={resource} />
    </div>
  )
}

function CanvasAudioPlayer({ resource }: { resource: Resource }) {
  const { t } = useI18n()
  const [originalRequested, setOriginalRequested] = useState(false)
  const {
    url: originalUrl,
    loading: originalLoading,
    error: originalError,
    refresh: refreshOriginal,
  } = useCanvasResourceUrl({
    canvasId: resource.canvasId,
    resourceId: resource.id,
    kind: 'original',
    enabled: originalRequested,
  })
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const pendingPlayRef = useRef(false)
  const [playing, setPlaying] = useState(false)
  const [currentTime, setCurrentTime] = useState(0)
  const [duration, setDuration] = useState(finiteSeconds(resource.durationMs) ?? 0)
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

  const requestOriginal = () => {
    if (originalRequested) {
      void refreshOriginal()
    } else {
      setOriginalRequested(true)
    }
  }

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

  const signing = originalLoading && !originalUrl
  return (
    <div className="audio-player nodrag nowheel" title={resource.name}>
      <span className="audio-player-format">{audioFormat(resource)}</span>
      <AudioWaveform />
      <div className="audio-player-controls">
        <span className="audio-player-time">
          {formatTime(currentTime)}
          {' / '}
          {formatTime(duration)}
        </span>
        <input
          type="range"
          className="audio-player-seek"
          min={0}
          max={Math.max(duration, 0.001)}
          step={0.1}
          value={Math.min(currentTime, Math.max(duration, 0.001))}
          style={rangeProgressStyle(currentTime, duration)}
          onChange={handleSeek}
          aria-label={t('canvas.audio.seekAria', { name: resource.name })}
        />
        <button
          type="button"
          className="audio-player-play"
          onClick={togglePlay}
          disabled={signing}
          aria-label={playing
            ? t('canvas.audio.pause', { name: resource.name })
            : t('canvas.audio.play', { name: resource.name })}
        >
          {signing ? (
            <LoaderCircle className="media-action-spinner" aria-hidden="true" />
          ) : playing ? (
            <Pause aria-hidden="true" />
          ) : (
            <Play aria-hidden="true" />
          )}
        </button>
        <div className="audio-player-volume-control">
          <input
            type="range"
            className="audio-player-volume"
            min={0}
            max={1}
            step={0.05}
            value={muted ? 0 : volume}
            style={rangeProgressStyle(muted ? 0 : volume, 1)}
            onChange={handleVolume}
            aria-label={t('canvas.audio.volumeAria', { name: resource.name })}
          />
          <button
            type="button"
            className="audio-player-mute"
            onClick={toggleMute}
            aria-label={muted
              ? t('canvas.audio.unmute', { name: resource.name })
              : t('canvas.audio.mute', { name: resource.name })}
            aria-pressed={muted}
          >
            {muted ? <VolumeX aria-hidden="true" /> : <Volume2 aria-hidden="true" />}
          </button>
        </div>
      </div>
      {playError ? <span className="audio-player-error" role="alert">{t('canvas.audio.playFailed')}</span> : null}
      {!playError && originalError ? <span className="audio-player-error" role="alert">{t('canvas.media.signFailed')}</span> : null}
      {originalUrl ? (
        <audio
          ref={(element) => {
            if (element) {
              audioRef.current = element
            }
          }}
          src={originalUrl}
          preload="metadata"
          className="nodrag nowheel"
          aria-label={t('canvas.audio.play', { name: resource.name })}
          onLoadedMetadata={(event) => {
            const audio = event.currentTarget
            if (Number.isFinite(audio.duration)) {
              setDuration(audio.duration)
            }
            setCurrentTime(audio.currentTime)
          }}
          onTimeUpdate={(event) => setCurrentTime(event.currentTarget.currentTime)}
          onEnded={() => setPlaying(false)}
          onPause={() => setPlaying(false)}
          onPlay={() => setPlaying(true)}
        />
      ) : null}
    </div>
  )
}

const AUDIO_WAVEFORM = [
  18, 30, 42, 26, 34, 48, 38, 24, 44, 32, 20, 36, 50, 40, 28, 46,
  34, 22, 38, 52, 42, 30, 46, 36, 24, 40, 48, 32, 26, 44, 34, 20,
] as const

function AudioWaveform() {
  return (
    <div className="audio-player-waveform" aria-hidden="true">
      {AUDIO_WAVEFORM.map((height, index) => (
        <span key={index} style={{ height: `${height}%` }} />
      ))}
    </div>
  )
}

function finiteSeconds(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value / 1000 : null
}

function formatTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) {
    return '0:00'
  }
  const total = Math.floor(seconds)
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
}

function rangeProgressStyle(value: number, max: number): CSSProperties {
  const progress = max > 0 && Number.isFinite(value)
    ? Math.max(0, Math.min(100, value / max * 100))
    : 0
  return { '--range-progress': `${progress}%` } as CSSProperties
}

function audioFormat(resource: Resource): string {
  const subtype = resource.mediaType?.split('/')[1]?.split(/[;+]/)[0]?.trim()
  if (subtype) {
    return subtype === 'mpeg' ? 'MP3' : subtype.toUpperCase()
  }
  const extension = resource.name.split('.').at(-1)?.trim()
  return extension ? extension.toUpperCase() : 'AUDIO'
}
