/**
 * RideVault Auth Relay — Cloudflare Worker
 *
 * Bridges Strava OAuth between a phone (browser) and the Karoo (app).
 *
 * Flow:
 *   1. Karoo shows QR code → phone opens /auth
 *   2. Worker redirects phone to Strava login
 *   3. User authorizes → Strava redirects to /callback
 *   4. Worker exchanges code for tokens, generates 6-digit PIN
 *   5. Phone displays the PIN
 *   6. User types PIN on Karoo → app calls /token?pin=XXXXXX
 *   7. Worker returns the tokens, deletes them from KV
 *
 * Environment variables (set as Worker secrets):
 *   STRAVA_CLIENT_ID     — from https://www.strava.com/settings/api
 *   STRAVA_CLIENT_SECRET — same page
 *
 * KV namespace binding:
 *   TOKENS — used for temporary PIN→token storage (5 min TTL)
 */

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, OPTIONS',
    };

    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders });
    }

    try {
      switch (url.pathname) {
        case '/auth':
          return handleAuth(url, env);
        case '/callback':
          return handleCallback(url, env);
        case '/token':
          return handleToken(url, env, corsHeaders);
        default:
          return new Response('RideVault Auth Relay', { status: 200 });
      }
    } catch (e) {
      return new Response(`Error: ${e.message}`, { status: 500 });
    }
  },
};

/**
 * Step 1: Redirect the phone browser to Strava's authorization page.
 */
function handleAuth(url, env) {
  const stravaUrl = new URL('https://www.strava.com/oauth/authorize');
  stravaUrl.searchParams.set('client_id', env.STRAVA_CLIENT_ID);
  stravaUrl.searchParams.set('redirect_uri', `${url.origin}/callback`);
  stravaUrl.searchParams.set('response_type', 'code');
  stravaUrl.searchParams.set('approval_prompt', 'force');
  stravaUrl.searchParams.set('scope', 'read,profile:read_all,activity:write,activity:read_all');
  return Response.redirect(stravaUrl.toString(), 302);
}

/**
 * Step 2: Strava redirects here with ?code=...
 * Exchange the code for tokens, generate a PIN, store in KV.
 */
async function handleCallback(url, env) {
  const code = url.searchParams.get('code');
  const error = url.searchParams.get('error');

  if (error || !code) {
    return htmlResponse(`
      <h1>Authorization Failed</h1>
      <p>${error || 'No authorization code received.'}</p>
      <p>Close this page and try again from your Karoo.</p>
    `, 400);
  }

  // Exchange code for tokens
  const tokenRes = await fetch('https://www.strava.com/oauth/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      client_id: env.STRAVA_CLIENT_ID,
      client_secret: env.STRAVA_CLIENT_SECRET,
      code,
      grant_type: 'authorization_code',
    }),
  });

  if (!tokenRes.ok) {
    const body = await tokenRes.text();
    return htmlResponse(`
      <h1>Token Exchange Failed</h1>
      <p>Strava returned an error. Close this page and try again.</p>
      <p style="color:#999;font-size:12px;">${body}</p>
    `, 500);
  }

  const tokenData = await tokenRes.json();

  // Generate a 6-digit PIN (100000–999999)
  const pin = String(100000 + crypto.getRandomValues(new Uint32Array(1))[0] % 900000);

  // Store in KV with a 5-minute TTL
  await env.TOKENS.put(pin, JSON.stringify(tokenData), { expirationTtl: 300 });

  const firstName = tokenData.athlete?.firstname || '';
  const greeting = firstName ? `, ${firstName}` : '';

  return htmlResponse(`
    <h1>Connected${greeting}!</h1>
    <p>Enter this PIN on your Karoo:</p>
    <div class="pin">${pin}</div>
    <p class="note">This PIN expires in 5 minutes.</p>
    <p class="note">You can close this page after entering the PIN.</p>
  `);
}

/**
 * Step 3: Karoo app calls this with ?pin=XXXXXX to retrieve tokens.
 */
async function handleToken(url, env, corsHeaders) {
  const pin = url.searchParams.get('pin');
  if (!pin || pin.length !== 6) {
    return Response.json(
      { error: 'Invalid PIN' },
      { status: 400, headers: corsHeaders },
    );
  }

  const data = await env.TOKENS.get(pin);
  if (!data) {
    return Response.json(
      { error: 'PIN not found or expired' },
      { status: 404, headers: corsHeaders },
    );
  }

  // One-time use: delete after retrieval
  await env.TOKENS.delete(pin);

  return new Response(data, {
    headers: { 'Content-Type': 'application/json', ...corsHeaders },
  });
}

/**
 * Simple HTML page wrapper for the phone browser.
 */
function htmlResponse(body, status = 200) {
  const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>RideVault</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      display: flex; justify-content: center; align-items: center;
      min-height: 100vh; padding: 24px;
      background: #f5f5f5; color: #333;
    }
    .card {
      background: white; border-radius: 16px; padding: 40px;
      text-align: center; max-width: 400px; width: 100%;
      box-shadow: 0 2px 12px rgba(0,0,0,0.1);
    }
    h1 { font-size: 24px; margin-bottom: 16px; }
    p { font-size: 16px; line-height: 1.5; margin-bottom: 12px; }
    .pin {
      font-size: 56px; font-weight: bold; letter-spacing: 12px;
      padding: 20px; margin: 24px 0;
      background: #f0f0f0; border-radius: 12px;
      font-family: "SF Mono", "Menlo", "Consolas", monospace;
    }
    .note { color: #999; font-size: 14px; }
  </style>
</head>
<body>
  <div class="card">${body}</div>
</body>
</html>`;

  return new Response(html, {
    status,
    headers: { 'Content-Type': 'text/html; charset=utf-8' },
  });
}
