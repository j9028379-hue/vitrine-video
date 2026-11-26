// WebCrypto HMAC-SHA256 & constant-time verification for Workers Free (0 external deps)

const te = new TextEncoder();

export function toHex(buffer) {
  return Array.from(new Uint8Array(buffer))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

export async function sha256Hex(textOrBytes) {
  const bytes = typeof textOrBytes === "string" ? te.encode(textOrBytes) : textOrBytes;
  return toHex(await crypto.subtle.digest("SHA-256", bytes));
}

export async function hmacSha256Raw(secret, message) {
  const key = await crypto.subtle.importKey(
    "raw",
    typeof secret === "string" ? te.encode(secret) : secret,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  return crypto.subtle.sign("HMAC", key, typeof message === "string" ? te.encode(message) : message);
}

export async function hmacSha256Hex(secret, message) {
  return toHex(await hmacSha256Raw(secret, message));
}

export function timingSafeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

function b64urlEncode(str) {
  return btoa(unescape(encodeURIComponent(str)))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

function b64urlDecode(str) {
  const pad = str.length % 4 ? "=".repeat(4 - (str.length % 4)) : "";
  return decodeURIComponent(escape(atob(str.replace(/-/g, "+").replace(/_/g, "/") + pad)));
}

export async function issueDeviceToken(secret, merchantId, ttlSec = 86400 * 30) {
  const payload = b64urlEncode(
    JSON.stringify({
      sub: merchantId,
      exp: Math.floor(Date.now() / 1000) + ttlSec,
    })
  );
  const sig = await hmacSha256Hex(secret, payload);
  return `${payload}.${sig}`;
}

export async function verifyDeviceToken(secret, authHeader) {
  if (!authHeader || !authHeader.startsWith("Bearer ")) return null;
  const token = authHeader.slice(7).trim();
  const dot = token.indexOf(".");
  if (dot <= 0) return null;
  const payload = token.slice(0, dot);
  const sig = token.slice(dot + 1);
  const expected = await hmacSha256Hex(secret, payload);
  if (!timingSafeEqual(expected, sig.toLowerCase())) return null;
  try {
    const parsed = JSON.parse(b64urlDecode(payload));
    if (!parsed.sub || !parsed.exp || parsed.exp < Math.floor(Date.now() / 1000)) return null;
    return parsed.sub;
  } catch {
    return null;
  }
}

export async function verifyWorkerHmac(secret, timestampStr, rawBody, signatureHex, maxSkewSec = 300) {
  if (!secret || !timestampStr || !signatureHex) return false;
  const ts = Number(timestampStr);
  if (!Number.isFinite(ts) || Math.abs(Math.floor(Date.now() / 1000) - ts) > maxSkewSec) {
    return false;
  }
  const expected = await hmacSha256Hex(secret, `${timestampStr}.${rawBody}`);
  return timingSafeEqual(expected, signatureHex.toLowerCase());
}
