import type { AppEnv } from './types';
import { HttpError, json, noContent, nowSeconds, readJson, requireUser } from './http';

function httpsUrl(value: string | undefined): string | null {
  try {
    const u = new URL(value || '');
    if (u.protocol !== 'https:' || u.username || u.password || u.hostname.endsWith('.invalid') ||
        ['localhost','127.0.0.1','[::1]'].includes(u.hostname)) return null;
    return u.href;
  } catch { return null; }
}
/** Only operator-published policies. Never synthesize a legal agreement from placeholders. */
export function policyConfig(env: AppEnv) {
  const version = /^[A-Za-z0-9._-]{1,64}$/.test(env.POLICY_VERSION || '') ? env.POLICY_VERSION! : null;
  const terms_url = httpsUrl(env.TERMS_URL), privacy_url = httpsUrl(env.PRIVACY_URL);
  const creator_terms_url = httpsUrl(env.CREATOR_TERMS_URL);
  const support_email = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(env.SUPPORT_EMAIL || '') ? env.SUPPORT_EMAIL! : null;
  return { ready: !!(version && terms_url && privacy_url && creator_terms_url && support_email),
    version, terms_url, privacy_url, creator_terms_url, support_email };
}
export function publicPolicy(env: AppEnv): Response { return json(200, policyConfig(env)); }
export function agreedVersion(env: AppEnv, supplied: unknown): string | null {
  const policy = policyConfig(env);
  if (!policy.ready) {
    if (env.DEV_MODE === '1') return null; // local fixtures only; never a production fallback
    throw new HttpError(503, 'policy_unavailable');
  }
  if (supplied !== policy.version) throw new HttpError(409, 'policy_required');
  return policy.version;
}
export async function acceptCreatorPolicy(request: Request, env: AppEnv): Promise<Response> {
  const user = await requireUser(request, env);
  const body = await readJson(request);
  if (body.accepted !== true) throw new HttpError(400, 'invalid_request');
  const version = agreedVersion(env, body.version);
  if (version) await env.DB.prepare(`INSERT OR IGNORE INTO policy_acceptances(user_id,kind,version,accepted_at)
    SELECT id,'creator',?,? FROM users WHERE id=? AND deletion_requested_at IS NULL`)
    .bind(version, nowSeconds(), user.id).run();
  return noContent();
}
export async function requireCreatorPolicy(userId: string, env: AppEnv): Promise<void> {
  const policy = policyConfig(env);
  if (!policy.ready) {
    if (env.DEV_MODE === '1') return;
    throw new HttpError(503, 'policy_unavailable');
  }
  const accepted = await env.DB.prepare("SELECT 1 FROM policy_acceptances WHERE user_id=? AND kind='creator' AND version=?")
    .bind(userId, policy.version).first();
  if (!accepted) throw new HttpError(409, 'policy_required');
}
