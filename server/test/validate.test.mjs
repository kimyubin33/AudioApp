import test from 'node:test';
import assert from 'node:assert/strict';
import {
  cleanMultiline,
  cleanText,
  isReportReason,
  isSafeHttpsUrl,
  isValidCursor,
  isValidId,
  parseLimit,
} from '../src/validate.ts';

test('id: 영문·숫자·-·_ 1~64자만', () => {
  assert.ok(isValidId('creator_01'));
  assert.ok(isValidId('a'.repeat(64)));
  for (const bad of ['', '../x', 'a b', 'a/b', 'a'.repeat(65), 'a\n', 1, null, undefined, '한글']) {
    assert.equal(isValidId(bad), false, String(bad));
  }
});

test('cursor: 주소에서 안전한 문자만', () => {
  assert.ok(isValidCursor('1000.sample-1'));
  for (const bad of ['', 'a&b', 'a b', 'a/../b', 'a'.repeat(257), 5]) assert.equal(isValidCursor(bad), false, String(bad));
});

test('cleanText: 공백 정리, 길이(글자 수), 제어 문자', () => {
  assert.equal(cleanText('  첫 곡  ', 100), '첫 곡');
  assert.equal(cleanText('가'.repeat(100), 100)?.length, 100);
  assert.equal(cleanText('가'.repeat(101), 100), null);
  assert.equal(cleanText('   ', 100), null);
  assert.equal(cleanText('a\nb', 100), null);
  assert.equal(cleanText('a\u0000b', 100), null);
  assert.equal(cleanText(5, 100), null);
  // 이모지는 한 글자로 센다 (Rust의 chars() 와 같다)
  assert.equal(cleanText('😀'.repeat(100), 100)?.length, 200);
  assert.equal(cleanText('😀'.repeat(101), 100), null);
});

test('cleanMultiline: 줄바꿈·탭 허용, 다른 제어 문자 불가', () => {
  assert.equal(cleanMultiline('a\nb\tc', 500), 'a\nb\tc');
  assert.equal(cleanMultiline('a\u0007b', 500), null);
  assert.equal(cleanMultiline('', 500), null);
  assert.equal(cleanMultiline('가'.repeat(501), 500), null);
});

test('limit: 생략=20, 1~50', () => {
  assert.equal(parseLimit(null), 20);
  assert.equal(parseLimit('1'), 1);
  assert.equal(parseLimit('50'), 50);
  for (const bad of ['0', '51', '-1', 'abc', '1.5', '', '0001000']) assert.equal(parseLimit(bad), null, bad);
});

test('신고 사유', () => {
  for (const ok of ['spam', 'inappropriate', 'copyright', 'missing_ai_label', 'other']) assert.ok(isReportReason(ok));
  assert.equal(isReportReason('SPAM'), false);
  assert.equal(isReportReason(undefined), false);
});

test('https 주소: https만, 계정정보·프래그먼트 불가, 서명 쿼리는 허용', () => {
  assert.ok(isSafeHttpsUrl('https://cdn.example.com/a.mp3?sig=abc'));
  for (const bad of [
    'http://cdn.example.com/a.mp3',
    'file:///sdcard/a.mp3',
    'javascript:alert(1)',
    'https://u:p@cdn.example.com/a.mp3',
    'https://cdn.example.com/a.mp3#x',
    'not a url',
    '',
    'https://cdn.example.com/\n',
    'https://a.com/' + 'x'.repeat(2100),
  ]) {
    assert.equal(isSafeHttpsUrl(bad), false, bad.slice(0, 40));
  }
});
