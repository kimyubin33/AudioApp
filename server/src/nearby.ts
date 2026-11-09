import { authenticate, HttpError, json, readJson } from './http';
import type { AppEnv } from './types';
import { boundingBox, coordinates, distanceMeters } from './geo';
import { validateSubmission } from './submission';

/** 위치를 URL·DB·로그에 넣지 않는 일회성 주변 검색. 공개 승인 메타데이터만 반환한다. */
export async function nearbyContents(request: Request, env: AppEnv): Promise<Response> {
  const user = await authenticate(request, env);
  const body = await readJson(request);
  const { latitude, longitude, radius_meters: radius } = body;
  if (!coordinates(latitude, longitude) || !Number.isInteger(radius) || (radius as number) < 100 || (radius as number) > 10000) {
    throw new HttpError(400, 'invalid_request');
  }
  const lat = latitude as number, lon = longitude as number, meters = radius as number;
  const box = boundingBox(lat, lon, meters);
  const where = ["c.status = 'approved'", 'c.place_latitude BETWEEN ? AND ?', 'c.place_longitude BETWEEN -180 AND 180'];
  const binds: (string | number)[] = [box.minLat, box.maxLat];
  if (box.minLon <= box.maxLon) {
    where.push('c.place_longitude BETWEEN ? AND ?');
  } else {
    where.push('(c.place_longitude >= ? OR c.place_longitude <= ?)');
  }
  binds.push(box.minLon, box.maxLon);
  if (user) {
    where.push('NOT EXISTS (SELECT 1 FROM blocks b WHERE b.user_id = ? AND b.creator_id = c.creator_id)');
    binds.push(user.id);
  }
  // 혼잡 지역도 요청당 메모리와 응답 크기를 제한한다. truncation을 숨기지 않는다.
  const { results } = await env.DB.prepare(`
    SELECT c.id, c.creator_id, c.title, c.kind, c.duration_seconds, c.ai_generated, c.ai_tool, c.submission_json
    FROM contents c WHERE ${where.join(' AND ')} ORDER BY c.created_at DESC, c.id DESC LIMIT 501
  `).bind(...binds).all<{
    id: string; creator_id: string; title: string; kind: string; duration_seconds: number;
    ai_generated: number; ai_tool: string | null; submission_json: string;
  }>();
  const found = [];
  for (const row of results.slice(0, 500)) {
    let place;
    try { place = validateSubmission(JSON.parse(row.submission_json))?.location; } catch { continue; }
    if (!place) continue;
    const distance = distanceMeters(lat, lon, place.latitude, place.longitude);
    if (distance > meters) continue;
    found.push({
      id: row.id, creator_id: row.creator_id, title: row.title, kind: row.kind,
      duration_seconds: row.duration_seconds, ai_generated: row.ai_generated === 1, ai_tool: row.ai_tool,
      distance_meters: Math.round(distance),
      location: { name: place.name, latitude: place.latitude, longitude: place.longitude,
        radius_meters: place.radius_meters, character: place.character, story: place.story },
    });
  }
  found.sort((a, b) => a.distance_meters - b.distance_meters || a.id.localeCompare(b.id));
  return json(200, { items: found.slice(0, 20), truncated: results.length > 500 || found.length > 20 });
}
