export interface Usage { used: number; total: number }

export interface SystemInfo {
  hostname: string
  os: string
  kernel: string
  uptime: number
  cpuUsage: number
  cpuCores: number
  load: number[]
  memory: Usage
  storage: Usage
  network: { interface: string; rxRate: number; txRate: number; rxTotal: number; txTotal: number }
  temperature: number | null
  time: number
}

export interface DetailDisk { mount: string; device: string; fs: string; used: number; total: number }
export interface DetailIface { name: string; ipv4: string[]; up: boolean; rxRate: number; txRate: number }
export interface DetailSensor { name: string; label: string; value: number }
export interface DetailProc { pid: number; name: string; cpu: number; mem: number }
export interface Details {
  cpuModel: string
  cpuMHz: number
  cores: number[]
  swap: Usage
  battery: { percent: number; status: string; acOnline: boolean } | null
  disks: DetailDisk[]
  interfaces: DetailIface[]
  sensors: DetailSensor[]
  topCpu: DetailProc[]
  topMem: DetailProc[]
}

export interface DsTotals { calls: number; input: number; output: number; cacheRead: number; costUsd: number }
export interface DeepSeekData {
  balance: { currency: string; total: number; granted: number; toppedUp: number; available: boolean; fetchedAt: number } | null
  balanceError?: string
  spend: { today: number; last7: number; last30: number; avgPerDay: number; daysLeft: number | null; topups7: number; trackedDays: number }
  daily: { date: string; spend: number; topup: number }[]
  since?: string
  samples: number
  hermes: {
    updatedAt: number
    stale: boolean
    today: DsTotals
    last7: DsTotals
    last30: DsTotals
    byModel: (DsTotals & { profile: string; model: string })[]
    daily: { date: string; input: number; output: number; cacheRead: number; calls: number }[]
  } | null
}

export interface History { cpu: number[]; memory: number[]; rxRate: number[]; txRate: number[] }

export interface Instance {
  name: string
  type: 'container' | 'virtual-machine'
  status: string
  running: boolean
  os: string
  release: string
  ipv4: string[]
  memoryUsed: number
  memoryLimit: number
  cpuSeconds: number
  cpuLimit: string
  processes: number
  since?: string
  created: string
}

export interface ServiceStatus {
  id: string
  name: string
  description: string
  kind: string
  status: 'up' | 'down'
  detail?: string
  latencyMs: number
  url?: string
  icon?: string
}

export interface MihomoStatus {
  version: string
  mode: string
  tun: boolean
  mixedPort: number
  connections: number
  uploadTotal: number
  downloadTotal: number
  memory: number
}

interface Envelope<T> { ok: boolean; data?: T; error?: string }

async function get<T>(path: string): Promise<T> {
  const ctl = new AbortController()
  const timer = setTimeout(() => ctl.abort(), 8000)
  let res: Response
  try {
    res = await fetch(`/api/v1${path}`, { headers: { Accept: 'application/json' }, signal: ctl.signal, cache: 'no-store' })
  } catch (e) {
    throw e instanceof DOMException && e.name === 'AbortError' ? new Error('Request timed out') : e
  } finally {
    clearTimeout(timer)
  }
  let body: Envelope<T> | undefined
  try { body = await res.json() } catch { /* not JSON */ }
  if (!res.ok || !body?.ok || body.data === undefined) {
    throw new Error(body?.error ?? `Request failed (${res.status})`)
  }
  return body.data
}

export const api = {
  system: () => get<SystemInfo>('/system'),
  history: () => get<History>('/metrics'),
  details: () => get<Details>('/details'),
  instances: () => get<Instance[]>('/instances'),
  services: () => get<ServiceStatus[]>('/services'),
  mihomo: () => get<MihomoStatus>('/mihomo'),
  deepseek: () => get<DeepSeekData>('/deepseek'),
}
