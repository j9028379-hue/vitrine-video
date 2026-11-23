// Mobile Money Webhook Verification (FedaPay, KkiaPay, CinetPay - UEMOA / XOF FCFA)

import { hmacSha256Hex, timingSafeEqual } from "./crypto.mjs";

const CREDITS_PER_PACK = [
  { minAmountXof: 5000, credits: 12 },
  { minAmountXof: 2500, credits: 5 },
  { minAmountXof: 500, credits: 1 },
];

export function computeCreditsForAmountXof(amountXof) {
  const amt = Number(amountXof);
  if (!Number.isFinite(amt) || amt < 500) return 0;
  for (const tier of CREDITS_PER_PACK) {
    if (amt >= tier.minAmountXof) return tier.credits;
  }
  return 0;
}

export async function verifyPaymentWebhook(provider, request, rawBody, env) {
  const p = provider.toLowerCase();
  if (p === "fedapay") {
    const header = request.headers.get("X-FEDAPAY-SIGNATURE") || "";
    const parts = Object.fromEntries(
      header.split(",").map((kv) => kv.split("=").map((s) => s.trim()))
    );
    if (!parts.t || !parts.s || !env.FEDAPAY_WEBHOOK_SECRET) return { valid: false, reason: "missing_signature" };
    const ts = Number(parts.t);
    if (!Number.isFinite(ts) || Math.abs(Math.floor(Date.now() / 1000) - ts) > 300) {
      return { valid: false, reason: "timestamp_expired" };
    }
    const expected = await hmacSha256Hex(env.FEDAPAY_WEBHOOK_SECRET, `${parts.t}.${rawBody}`);
    if (!timingSafeEqual(expected, parts.s.toLowerCase())) {
      return { valid: false, reason: "bad_hmac" };
    }
    const ev = JSON.parse(rawBody);
    const tx = ev.entity || ev.data || ev;
    const paid = ev.name === "transaction.approved" || tx.status === "approved";
    return {
      valid: true,
      paid,
      externalTxId: String(tx.id || ev.id || ""),
      merchantId: String(tx.custom_metadata?.merchant_id || tx.metadata?.merchant_id || ""),
      amountXof: Number(tx.amount || 0),
    };
  }

  if (p === "kkiapay") {
    const sig = request.headers.get("x-kkiapay-secret") || "";
    if (!sig || !env.KKIAPAY_WEBHOOK_SECRET) return { valid: false, reason: "missing_signature" };
    const expected = await hmacSha256Hex(env.KKIAPAY_WEBHOOK_SECRET, rawBody);
    if (!timingSafeEqual(expected, sig.toLowerCase())) {
      return { valid: false, reason: "bad_hmac" };
    }
    const tx = JSON.parse(rawBody);
    return {
      valid: true,
      paid: Boolean(tx.isPaymentSucces || tx.status === "SUCCESS"),
      externalTxId: String(tx.transactionId || tx.id || ""),
      merchantId: String(tx.stateData?.merchant_id || tx.partnerId || ""),
      amountXof: Number(tx.amount || 0),
    };
  }

  if (p === "cinetpay") {
    const token = request.headers.get("x-token") || "";
    if (!token || !env.CINETPAY_WEBHOOK_SECRET) return { valid: false, reason: "missing_signature" };
    const tx = JSON.parse(rawBody);
    const msg = `${tx.cpm_site_id || ""}${tx.cpm_trans_id || ""}${tx.cpm_trans_date || ""}${tx.cpm_amount || ""}${tx.cpm_currency || "XOF"}`;
    const expected = await hmacSha256Hex(env.CINETPAY_WEBHOOK_SECRET, msg);
    if (!timingSafeEqual(expected, token.toLowerCase())) {
      return { valid: false, reason: "bad_hmac" };
    }
    return {
      valid: true,
      paid: String(tx.cpm_result) === "00" || tx.status === "ACCEPTED",
      externalTxId: String(tx.cpm_trans_id || ""),
      merchantId: String(tx.cpm_custom || ""),
      amountXof: Number(tx.cpm_amount || 0),
    };
  }

  return { valid: false, reason: "unsupported_provider" };
}
