// Zero-dependency WebCrypto AWS Signature v4 presigned URL generator for Cloudflare R2.
// Runs in < 1 ms CPU inside Cloudflare Workers Free (10 ms CPU budget).

import { hmacSha256Raw, sha256Hex, toHex } from "./crypto.mjs";

function encodeRfc3986Path(path) {
  return path
    .split("/")
    .map((seg) => encodeURIComponent(seg).replace(/[!'()*]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`))
    .join("/");
}

export async function signR2Url({
  accountId,
  accessKeyId,
  secretAccessKey,
  bucketName,
  objectKey,
  method = "GET",
  expiresSec = 600,
  contentType = null,
  now = new Date(),
}) {
  const host = `${accountId}.r2.cloudflarestorage.com`;
  const amzDate = now.toISOString().replace(/[:-]|\.\d{3}/g, "");
  const dateStamp = amzDate.slice(0, 8);
  const region = "auto";
  const service = "s3";
  const credentialScope = `${dateStamp}/${region}/${service}/aws4_request`;

  const signedHeaders = contentType ? "content-type;host" : "host";
  const canonicalHeaders = contentType
    ? `content-type:${contentType}\nhost:${host}\n`
    : `host:${host}\n`;

  const params = new URLSearchParams([
    ["X-Amz-Algorithm", "AWS4-HMAC-SHA256"],
    ["X-Amz-Credential", `${accessKeyId}/${credentialScope}`],
    ["X-Amz-Date", amzDate],
    ["X-Amz-Expires", String(expiresSec)],
    ["X-Amz-SignedHeaders", signedHeaders],
  ]);
  params.sort();

  const canonicalUri = `/${encodeRfc3986Path(bucketName)}/${encodeRfc3986Path(objectKey)}`;
  const canonicalRequest = [
    method.toUpperCase(),
    canonicalUri,
    params.toString(),
    canonicalHeaders,
    signedHeaders,
    "UNSIGNED-PAYLOAD",
  ].join("\n");

  const stringToSign = [
    "AWS4-HMAC-SHA256",
    amzDate,
    credentialScope,
    await sha256Hex(canonicalRequest),
  ].join("\n");

  const kDate = await hmacSha256Raw(`AWS4${secretAccessKey}`, dateStamp);
  const kRegion = await hmacSha256Raw(kDate, region);
  const kService = await hmacSha256Raw(kRegion, service);
  const kSigning = await hmacSha256Raw(kService, "aws4_request");
  const signature = toHex(await hmacSha256Raw(kSigning, stringToSign));

  return `https://${host}${canonicalUri}?${params.toString()}&X-Amz-Signature=${signature}`;
}
