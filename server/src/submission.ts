import { cleanText, cleanMultiline, isSafeHttpsUrl, isValidId } from './validate.ts';
import { MAX_UPLOAD_BYTES } from './upload-validation.ts';

const object = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === 'object' && !Array.isArray(v);
const text = (v: unknown, max: number) => v === undefined || v === null || v === '' ? '' : cleanText(v, max);

/** 버전이 있는 제출 명세. 권리 확인은 신고/심사를 위한 진술이며 권리의 자동 인증이 아니다. */
export function validateSubmission(raw: unknown, allowNoFile = false) {
  if (!object(raw) || raw.version !== 1 || !object(raw.rights)) return null;
  const r = raw.rights;
  let file: { name: string; size: number; sha256: string } | null = null;
  if (raw.file !== null && raw.file !== undefined) {
    const f = raw.file;
    if (!object(f)) return null;
    const name = cleanText(f.name, 255);
    if (!name || /[/\\\\]/.test(name) || !Number.isSafeInteger(f.size) || (f.size as number) < 1 ||
        (f.size as number) > MAX_UPLOAD_BYTES || typeof f.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(f.sha256)) return null;
    file = { name, size: f.size as number, sha256: f.sha256 };
  } else if (!allowNoFile) return null;
  const audioId = raw.audio_content_id ?? null;
  if (audioId !== null && !isValidId(audioId)) return null;
  const holder = cleanText(r.holder, 100);
  const attribution = r.attribution === '' || r.attribution === undefined ? '' : cleanMultiline(r.attribution, 1000);
  const source = text(r.source_url, 2048);
  if (!holder || attribution === null || source === null || (source !== '' && !isSafeHttpsUrl(source)) ||
      (typeof r.license !== 'string' || !['original', 'licensed', 'public_domain'].includes(r.license)) ||
      r.distribution_confirmed !== true || r.person_consent_confirmed !== true || r.ai_terms_confirmed !== true ||
      (r.license !== 'original' && attribution === '')) return null;
  const description = raw.description === '' || raw.description === undefined ? '' : cleanMultiline(raw.description, 2000);
  if (description === null || !Array.isArray(raw.tags) || raw.tags.length > 10) return null;
  const tags = raw.tags.map(t => cleanText(t, 30));
  if (tags.some(t => t === null) || new Set(tags).size !== tags.length) return null;
  let location = null;
  if (raw.location !== null && raw.location !== undefined) {
    const p = raw.location;
    if (!object(p)) return null;
    const place = cleanText(p.name, 100), character = text(p.character, 100);
    const story = p.story === '' || p.story === undefined ? '' : cleanMultiline(p.story, 2000);
    if (!place || character === null || story === null || typeof p.latitude !== 'number' || !Number.isFinite(p.latitude) ||
        p.latitude < -90 || p.latitude > 90 || typeof p.longitude !== 'number' || !Number.isFinite(p.longitude) ||
        p.longitude < -180 || p.longitude > 180 || !Number.isInteger(p.radius_meters) ||
        (p.radius_meters as number) < 50 || (p.radius_meters as number) > 5000 || p.public_location_confirmed !== true) return null;
    location = { name: place, latitude: p.latitude, longitude: p.longitude, radius_meters: p.radius_meters, character, story, public_location_confirmed: true };
  }
  return { version: 1, description, tags, file, ...(audioId ? { audio_content_id: audioId } : {}),
    rights: { holder, license: r.license, attribution, source_url: source, distribution_confirmed: true, person_consent_confirmed: true, ai_terms_confirmed: true },
    location };
}
