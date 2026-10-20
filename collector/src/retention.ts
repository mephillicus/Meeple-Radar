export const RETENTION_DAYS = 365

export function retentionCutoff(now: Date = new Date()): string {
  const cutoff = new Date(now.getTime())
  cutoff.setUTCDate(cutoff.getUTCDate() - RETENTION_DAYS)
  return cutoff.toISOString().slice(0, 10)
}
