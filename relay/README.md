# RideVault Auth Relay

A Cloudflare Worker that bridges Strava OAuth between your phone and the Karoo.

## How it works

1. Karoo shows a QR code → scan with your phone
2. Phone opens Strava login → you authorize
3. Phone shows a 6-digit PIN
4. Type the PIN on your Karoo → connected!

## One-time setup

### 1. Create a free Cloudflare account

Go to https://dash.cloudflare.com/sign-up (no credit card needed).

### 2. Install Wrangler (Cloudflare CLI)

```bash
npm install -g wrangler
wrangler login
```

### 3. Create the KV namespace

```bash
cd relay
npx wrangler kv namespace create TOKENS
```

Copy the `id` from the output and paste it into `wrangler.toml`.

### 4. Set your Strava API secrets

```bash
npx wrangler secret put STRAVA_CLIENT_ID
npx wrangler secret put STRAVA_CLIENT_SECRET
```

Enter the values from https://www.strava.com/settings/api when prompted.

### 5. Deploy

```bash
npx wrangler deploy
```

Note the URL it prints (e.g., `https://ridevault-auth.<you>.workers.dev`).

### 6. Update Strava callback domain

Go to https://www.strava.com/settings/api and set **Authorization Callback Domain** to your Worker domain (e.g., `ridevault-auth.<you>.workers.dev`).

### 7. Configure the Karoo app

Add to your `gradle.properties`:

```
relay.url=https://ridevault-auth.<you>.workers.dev
```

Rebuild and install the app.
