/** WGS84 좌표의 구면 거리. 날짜변경선과 극지방을 포함한다. 좌표는 저장하지 않는다. */
export const EARTH_METERS = 6371008.8;
export function coordinates(latitude: unknown, longitude: unknown): boolean {
  return typeof latitude === 'number' && Number.isFinite(latitude) && latitude >= -90 && latitude <= 90 &&
    typeof longitude === 'number' && Number.isFinite(longitude) && longitude >= -180 && longitude <= 180;
}
export function distanceMeters(a: number, b: number, c: number, d: number): number {
  const rad = Math.PI / 180;
  const h = Math.sin((c - a) * rad / 2) ** 2 + Math.cos(a * rad) * Math.cos(c * rad) * Math.sin((d - b) * rad / 2) ** 2;
  return 2 * EARTH_METERS * Math.asin(Math.sqrt(Math.min(1, Math.max(0, h))));
}
export function boundingBox(latitude: number, longitude: number, radius: number) {
  const delta = radius / EARTH_METERS;
  const lat = latitude * Math.PI / 180;
  const minLat = Math.max(-90, latitude - delta * 180 / Math.PI);
  const maxLat = Math.min(90, latitude + delta * 180 / Math.PI);
  if (minLat === -90 || maxLat === 90) return { minLat, maxLat, minLon: -180, maxLon: 180 };
  const lonDelta = Math.asin(Math.min(1, Math.sin(delta) / Math.cos(lat))) * 180 / Math.PI;
  const wrap = (v: number) => ((v + 540) % 360) - 180;
  return { minLat, maxLat, minLon: wrap(longitude - lonDelta), maxLon: wrap(longitude + lonDelta) };
}
