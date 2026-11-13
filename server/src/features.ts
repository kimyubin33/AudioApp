import type { AppEnv } from './types';
import { HttpError } from './http';
/** Extended formats remain available only for explicitly opted-in local regression tests. */
export const extendedFormats = (env: AppEnv) => env.DEV_MODE === '1' && env.ENABLE_EXTENDED_FORMATS === '1';
export function requireFormat(kind: string, env: AppEnv): void {
 if (kind !== 'audio' && !extendedFormats(env)) throw new HttpError(403, 'format_unavailable');
}
