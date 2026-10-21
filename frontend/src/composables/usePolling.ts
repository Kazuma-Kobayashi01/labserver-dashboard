import { onMounted, onUnmounted, ref, shallowRef, type Ref, type ShallowRef } from 'vue'

export interface Polled<T> {
  data: ShallowRef<T | undefined>
  error: Ref<string | null>
  loading: Ref<boolean>
  refresh: () => Promise<void>
}

/** Calls `fn` immediately and then every `ms`, pausing while the tab is hidden. */
export function usePolling<T>(fn: () => Promise<T>, ms: number): Polled<T> {
  const data = shallowRef<T>()
  const error = ref<string | null>(null)
  const loading = ref(true)
  let timer: ReturnType<typeof setInterval> | undefined
  let inflight = false

  async function refresh() {
    if (inflight) return
    inflight = true
    try {
      data.value = await fn()
      error.value = null
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Unknown error'
    } finally {
      loading.value = false
      inflight = false
    }
  }

  const onVisible = () => { if (!document.hidden) void refresh() }

  onMounted(() => {
    void refresh()
    timer = setInterval(() => { if (!document.hidden) void refresh() }, ms)
    document.addEventListener('visibilitychange', onVisible)
  })
  onUnmounted(() => {
    clearInterval(timer)
    document.removeEventListener('visibilitychange', onVisible)
  })

  return { data, error, loading, refresh }
}
