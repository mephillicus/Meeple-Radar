export function loadConfig(env: NodeJS.ProcessEnv): {
  supabaseUrl: string
  supabaseServiceKey: string
} {
  const supabaseUrl = env.SUPABASE_URL
  const supabaseServiceKey = env.SUPABASE_SERVICE_ROLE_KEY
  if (!supabaseUrl) throw new Error('SUPABASE_URL mancante')
  if (!supabaseServiceKey) throw new Error('SUPABASE_SERVICE_ROLE_KEY mancante')
  return { supabaseUrl, supabaseServiceKey }
}
