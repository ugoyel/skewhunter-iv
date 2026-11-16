// Supabase "Send SMS" auth hook: instead of an SMS, the sign-in code is delivered as a Telegram message
// through the Telegram Gateway API (https://core.telegram.org/gateway). Costs about US$0.01 per code.
//
// Secrets (Project Settings -> Edge Functions -> Secrets):
//   SEND_SMS_HOOK_SECRET    the hook secret Supabase shows when the hook is created ("v1,whsec_...")
//   TELEGRAM_GATEWAY_TOKEN  the API token from gateway.telegram.org
import { Webhook } from "https://esm.sh/standardwebhooks@1.0.0";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

Deno.serve(async (req) => {
  const payload = await req.text();
  const secret = (Deno.env.get("SEND_SMS_HOOK_SECRET") ?? "").replace("v1,whsec_", "");
  let data: { user: { phone: string }; sms: { otp: string } };
  try {
    data = new Webhook(secret).verify(payload, Object.fromEntries(req.headers)) as typeof data;
  } catch {
    return json({ error: { http_code: 401, message: "Invalid hook signature" } }, 401);
  }
  const token = Deno.env.get("TELEGRAM_GATEWAY_TOKEN");
  if (!token) return json({ error: { http_code: 500, message: "Telegram sign-in is not set up yet" } }, 500);
  const phone = data.user.phone.startsWith("+") ? data.user.phone : "+" + data.user.phone;
  const r = await fetch("https://gatewayapi.telegram.org/sendVerificationMessage", {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ phone_number: phone, code: data.sms.otp, ttl: 300 }),
  });
  const out = await r.json().catch(() => ({ ok: false, error: "no response" }));
  if (!out.ok) {
    const why = String(out.error ?? "unknown");
    const msg = why.includes("PHONE_NUMBER") || why.includes("NOT_FOUND")
      ? "This number has no Telegram account. Sign up with e-mail instead."
      : "Telegram could not send the code (" + why + ")";
    return json({ error: { http_code: 400, message: msg } }, 400);
  }
  return json({});
});
