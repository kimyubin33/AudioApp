import test from 'node:test';
import assert from 'node:assert/strict';
import { boundingBox, coordinates, distanceMeters } from '../src/geo.ts';

test('주변 검색: 날짜변경선과 극지방의 경계 및 구면 거리', () => {
  assert.equal(distanceMeters(37, 127, 37, 127), 0);
  assert.ok(Math.abs(distanceMeters(0, 0, 0, 1) - 111195) < 1);
  assert.ok(distanceMeters(0, 179.999, 0, -179.999) < 223);
  const crossing = boundingBox(0, 179.999, 1000);
  assert.ok(crossing.minLon > crossing.maxLon);
  assert.deepEqual(boundingBox(90, 0, 1000).minLon, -180);
  assert.deepEqual(boundingBox(-90, 0, 1000).maxLon, 180);
  for (const pair of [[NaN, 0], [0, Infinity], [91, 0], [0, -181], ['37', 127], [null, 0]]) assert.equal(coordinates(...pair), false);
});
