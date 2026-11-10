// Seals small JSON payloads (the PayPal vault reference) with AES-256-GCM so the phone can keep them
// without the server storing anything. The phone can't read or forge them; only this server can.
import crypto from 'node:crypto';

const RAW = process.env.LINK_SECRET || process.env.PAYPAL_CLIENT_SECRET || 'dev-only-secret';
const KEY = crypto.createHash('sha256').update('hushhboard-link:' + RAW).digest();

export function seal(obj) {
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-256-gcm', KEY, iv);
  const body = Buffer.concat([c.update(JSON.stringify(obj), 'utf8'), c.final()]);
  return Buffer.concat([iv, c.getAuthTag(), body]).toString('base64url');
}

export function open(token) {
  try {
    const buf = Buffer.from(String(token || ''), 'base64url');
    if (buf.length < 29) return null;
    const d = crypto.createDecipheriv('aes-256-gcm', KEY, buf.subarray(0, 12));
    d.setAuthTag(buf.subarray(12, 28));
    return JSON.parse(Buffer.concat([d.update(buf.subarray(28)), d.final()]).toString('utf8'));
  } catch {
    return null;
  }
}
