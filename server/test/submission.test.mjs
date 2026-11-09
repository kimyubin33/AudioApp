import test from 'node:test';
import assert from 'node:assert/strict';
import { validateSubmission } from '../src/submission.ts';

const good = () => ({
  version: 1, description: '설명', tags: ['audio'],
  file: { name: 'a.mp3', size: 1024, sha256: 'a'.repeat(64) },
  rights: { holder: '권리자', license: 'original', attribution: '', source_url: '',
    distribution_confirmed: true, person_consent_confirmed: true, ai_terms_confirmed: true },
  location: { name: '공원', latitude: 37.5, longitude: 127, radius_meters: 200, character: '달빛', story: '장소 이야기', public_location_confirmed: true },
});
test('제출 명세: 파일·권리·공개 장소를 정규화하고 저장 가능한 모양만 허용', () => {
  assert.ok(validateSubmission(good()));
  for (const mutate of [
    x => x.file.name = '../a.mp3', x => x.file.name = 'a\\b.mp3', x => x.file.size = 0,
    x => x.file.size = 94371841, x => x.file.sha256 = 'invalid',
    x => x.rights.distribution_confirmed = false, x => x.rights.person_consent_confirmed = false,
    x => x.rights.ai_terms_confirmed = false, x => x.rights.license = 'licensed',
    x => x.rights.source_url = 'http://example.com', x => x.tags = ['x', 'x'],
    x => x.location.latitude = 91, x => x.location.longitude = -181,
    x => x.location.radius_meters = 49, x => x.location.public_location_confirmed = false,
  ]) { const x = good(); mutate(x); assert.equal(validateSubmission(x), null, JSON.stringify(x)); }
});
