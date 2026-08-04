import { onBeforeUnmount, onMounted } from 'vue'
import type { PositionAnalysisStatus, PositionAnalysisTaskStatus } from '@/types'

interface PositionAnalysisPollingOptions {
  fetchStatus: (positionId: number, signal: AbortSignal) => Promise<PositionAnalysisStatus>
  onStatus: (status: PositionAnalysisStatus) => void | Promise<void>
  intervalMs?: number
}

export function isPositionTaskActive(status?: PositionAnalysisTaskStatus): boolean {
  return status === 'WAITING' || status === 'RUNNING'
}

export function usePositionAnalysisPolling(options: PositionAnalysisPollingOptions) {
  const intervalMs = options.intervalMs ?? 2000
  const pollingIds = new Set<number>()
  const timers = new Map<number, number>()
  const requests = new Map<number, AbortController>()
  let disposed = false

  function start(positionId: number) {
    pollingIds.add(positionId)
    schedule(positionId, 0)
  }

  function stop(positionId: number) {
    pollingIds.delete(positionId)
    const timer = timers.get(positionId)
    if (timer !== undefined) window.clearTimeout(timer)
    timers.delete(positionId)
    requests.get(positionId)?.abort()
    requests.delete(positionId)
  }

  function sync(positionIds: Iterable<number>) {
    const nextIds = new Set(positionIds)
    Array.from(pollingIds).forEach(positionId => {
      if (!nextIds.has(positionId)) stop(positionId)
    })
    nextIds.forEach(start)
  }

  function stopAll() {
    Array.from(pollingIds).forEach(stop)
  }

  function schedule(positionId: number, delay = intervalMs) {
    if (
      disposed
      || !pollingIds.has(positionId)
      || document.visibilityState !== 'visible'
      || timers.has(positionId)
      || requests.has(positionId)
    ) {
      return
    }
    const timer = window.setTimeout(() => {
      timers.delete(positionId)
      void poll(positionId)
    }, delay)
    timers.set(positionId, timer)
  }

  async function poll(positionId: number) {
    if (disposed || !pollingIds.has(positionId) || document.visibilityState !== 'visible') return
    const controller = new AbortController()
    requests.set(positionId, controller)
    let shouldContinue = false
    let reachedTerminalStatus = false
    try {
      const status = await options.fetchStatus(positionId, controller.signal)
      if (disposed || controller.signal.aborted || !pollingIds.has(positionId)) return
      await options.onStatus(status)
      shouldContinue = isPositionTaskActive(status.latestTaskStatus)
      reachedTerminalStatus = !shouldContinue
    } catch {
      shouldContinue = !disposed && !controller.signal.aborted && pollingIds.has(positionId)
    } finally {
      if (requests.get(positionId) === controller) {
        requests.delete(positionId)
      }
      if (reachedTerminalStatus) {
        stop(positionId)
      } else if (shouldContinue) {
        schedule(positionId)
      }
    }
  }

  function pauseForHiddenPage() {
    timers.forEach(timer => window.clearTimeout(timer))
    timers.clear()
    requests.forEach(controller => controller.abort())
    requests.clear()
  }

  function handleVisibilityChange() {
    if (document.visibilityState !== 'visible') {
      pauseForHiddenPage()
      return
    }
    pollingIds.forEach(positionId => schedule(positionId, 0))
  }

  onMounted(() => {
    document.addEventListener('visibilitychange', handleVisibilityChange)
  })

  onBeforeUnmount(() => {
    disposed = true
    stopAll()
    document.removeEventListener('visibilitychange', handleVisibilityChange)
  })

  return { start, stop, sync, stopAll }
}
