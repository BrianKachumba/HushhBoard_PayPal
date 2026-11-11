// HushhBoard money server - stateless.
// - Keeps the PayPal client secret off the phone.
// - "Connect PayPal" = PayPal Vault: setup token -> user approves on PayPal's own pages -> payment token.
//   The payment-token reference is sealed (AES-256-GCM) and handed to the phone. The server stores nothing,
//   so free hosting restarts never lose anyone's link. The sealed link is bound to the phone's device token,
//   so the link alone can't be used from another device. We never see a PayPal password.
import crypto from 'node:crypto';
import express from 'express';
import * as paypal from './paypal.js';
import { seal, open } from './seal.js';
import { searchProducts, productsConfigured } from './products.js';

const app = express();
app.use(express.json({ limit: '32kb' }));

const PORT = process.env.PORT || 8080;
const PUBLIC_URL = (process.env.PUBLIC_URL || process.env.RENDER_EXTERNAL_URL || `http://localhost:${PORT}`).replace(/\/$/, '');
const APP_LINK = 'hushhboard://paypal';
const KES_PER_USD = Number(process.env.FX_KES_PER_USD || 129.96);
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
const CURRENCIES = new Set(['USD', 'EUR', 'GBP']);
const deviceHash = (d) => crypto.createHash('sha256').update('dev:' + d).digest('base64url').slice(0, 22);

// Fallback only (PayPal normally echoes the setup token id on the return URL).
const pendingSetups = new Map();

// ---------- helpers ----------
function device(req, res) {
  const token = req.get('X-Hushh-Device') || '';
  if (!/^[A-Za-z0-9-]{16,64}$/.test(token)) {
    res.status(401).json({ error: 'DEVICE_TOKEN_REQUIRED', message: 'Missing or invalid X-Hushh-Device header.' });
    return null;
  }
  return token;
}

/** The phone's sealed PayPal link -> { v: vaultId, e: email, n: name }, bound to this device. */
function link(req, res, dev) {
  const data = open(req.get('X-Hushh-Link'));
  if (!data || data.d !== deviceHash(dev)) {
    res.status(409).json({ error: 'PAYPAL_NOT_CONNECTED', message: 'Connect PayPal first.' });
    return null;
  }
  return data;
}

function parseMoney(body, res) {
  const amount = Number(body.amount);
  const currency = String(body.currency || 'USD').toUpperCase();
  if (!Number.isFinite(amount) || amount < 0.01 || amount > 10000) {
    res.status(400).json({ error: 'INVALID_AMOUNT', message: 'Amount must be between 0.01 and 10,000.' });
    return null;
  }
  if (!CURRENCIES.has(currency)) {
    res.status(400).json({ error: 'UNSUPPORTED_CURRENCY', message: `Use one of ${[...CURRENCIES].join(', ')}. PayPal does not hold KES.` });
    return null;
  }
  return { amount: Math.round(amount * 100) / 100, currency };
}

function fail(res, err) {
  const status = err instanceof paypal.PayPalError ? (err.issue === 'PAYPAL_RELINK' ? 409 : err.status >= 500 ? 502 : 422) : 500;
  console.error('[paypal]', err.message);
  res.status(status).json({ error: err.issue || 'SERVER_ERROR', message: err.message });
}

const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const page = (title, message, { href, label, autoOpen, ok = true } = {}) => `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex,nofollow">
<title>HushhBoard · ${esc(title)}</title>
<style>
 body{font-family:system-ui,-apple-system,Roboto,sans-serif;background:#0A0A0A;color:#fff;display:flex;min-height:100vh;align-items:center;justify-content:center;margin:0}
 .card{max-width:420px;padding:32px 24px;text-align:center}
 h1{font-size:22px;margin:16px 0 8px} p{color:#aaa;line-height:1.5}
 .dot{width:56px;height:56px;border-radius:50%;background:${ok ? '#00E676' : '#FFB300'};margin:0 auto;display:flex;align-items:center;justify-content:center;color:#000;font-size:28px;font-weight:700}
 a.btn{display:inline-block;margin-top:20px;background:#00E676;color:#000;padding:14px 22px;border-radius:12px;font-weight:600;text-decoration:none}
</style></head>
<body><div class="card"><div class="dot">${ok ? '&#10003;' : '!'}</div><h1>${esc(title)}</h1><p>${esc(message)}</p>
${href ? `<a class="btn" href="${esc(href)}">${esc(label)}</a>` : ''}
</div>${autoOpen && href ? `<script>setTimeout(function(){location.href=${JSON.stringify(href)}},600)</script>` : ''}</body></html>`;

// ---------- health / config ----------
app.get('/', (_req, res) => res.json({ service: 'hushhboard-money', paypal: paypal.configured() ? 'sandbox-ready' : 'missing-credentials', products: productsConfigured() ? 'channel3-ready' : 'off' }));
app.get('/health', (_req, res) => res.json({ ok: true }));
app.get('/api/fx', (_req, res) => res.json({ base: 'USD', quote: 'KES', rate: KES_PER_USD, note: 'Indicative CBK mean rate; PayPal applies its own conversion.' }));

// ---------- PayPal connect (Vault) ----------
app.post('/api/paypal/connect', async (req, res) => {
  const dev = device(req, res); if (!dev) return;
  try {
    const state = seal({ d: deviceHash(dev), t: Date.now(), r: crypto.randomUUID() });
    const setup = await paypal.createSetupToken({
      returnUrl: `${PUBLIC_URL}/link/done?state=${state}`,
      cancelUrl: `${PUBLIC_URL}/link/cancel`,
      requestId: crypto.randomUUID(),
    });
    pendingSetups.set(state, setup.id);
    setTimeout(() => pendingSetups.delete(state), 30 * 60_000).unref();
    res.json({ approveUrl: setup.approveUrl });
  } catch (err) { fail(res, err); }
});

app.get(['/link/done', '/paypal/connected'], async (req, res) => {
  const stateRaw = String(req.query.state || '');
  const state = open(stateRaw);
  const setupTokenId = String(req.query.approval_token_id || req.query.approval_session_id || req.query.token || pendingSetups.get(stateRaw) || '');
  const back = { href: `${APP_LINK}?result=error`, label: 'Back to HushhBoard', ok: false };
  if (!state || Date.now() - state.t > 30 * 60_000 || !setupTokenId) {
    return res.status(400).send(page('Link expired', 'Open HushhBoard and tap Connect PayPal again.', back));
  }
  try {
    const pt = await paypal.createPaymentToken(setupTokenId);
    pendingSetups.delete(stateRaw);
    const sealed = seal({ v: pt.id, e: pt.email, n: pt.name, d: state.d });
    const href = `${APP_LINK}?result=connected&email=${encodeURIComponent(pt.email)}&name=${encodeURIComponent(pt.name)}&link=${sealed}`;
    res.send(page('Linked', `${pt.email || 'Your account'} is linked. Taking you back to HushhBoard.`, { href, label: 'Back to HushhBoard', autoOpen: true }));
  } catch (err) {
    console.error('[connect]', err.message);
    res.status(502).send(page('Not linked', 'The link was not confirmed. Please try again from HushhBoard.', back));
  }
});

app.get(['/link/cancel', '/paypal/cancelled'], (_req, res) =>
  res.send(page('Not linked', 'You cancelled. Nothing was linked.', { href: `${APP_LINK}?result=cancelled`, label: 'Back to HushhBoard', autoOpen: true, ok: false })));

app.get('/api/paypal/status', (req, res) => {
  const dev = device(req, res); if (!dev) return;
  const data = open(req.get('X-Hushh-Link'));
  const ok = Boolean(data && data.d === deviceHash(dev));
  res.json({ connected: ok, email: ok ? data.e : null, name: ok ? data.n : null });
});

app.post('/api/paypal/disconnect', async (req, res) => {
  const dev = device(req, res); if (!dev) return;
  const pp = link(req, res, dev); if (!pp) return;
  await paypal.deletePaymentToken(pp.v).catch((e) => console.warn('[disconnect]', e.message));
  res.json({ connected: false });
});

// ---------- Pay with the saved PayPal ----------
app.post('/api/paypal/send', async (req, res) => {
  const dev = device(req, res); if (!dev) return;
  const pp = link(req, res, dev); if (!pp) return;
  const m = parseMoney(req.body || {}, res); if (!m) return;
  const to = String(req.body.to || '').trim().toLowerCase();
  if (!EMAIL.test(to)) return res.status(400).json({ error: 'INVALID_PAYEE', message: "Enter the receiver's PayPal email." });
  if (to === String(pp.e || '').toLowerCase()) return res.status(400).json({ error: 'SAME_ACCOUNT', message: "You can't send to your own PayPal." });
  try {
    const result = await paypal.payWithVault({
      vaultId: pp.v, payeeEmail: to, amount: m.amount, currency: m.currency, note: req.body.note,
      requestId: String(req.get('Idempotency-Key') || crypto.randomUUID()),
    });
    res.json({ ...result, kesEstimate: m.currency === 'USD' ? Math.round(m.amount * KES_PER_USD) : null });
  } catch (err) { fail(res, err); }
});

// ---------- Request money into the user's PayPal ----------
app.post('/api/paypal/request', async (req, res) => {
  const dev = device(req, res); if (!dev) return;
  const pp = link(req, res, dev); if (!pp) return;
  if (!pp.e) return res.status(409).json({ error: 'PAYPAL_NOT_CONNECTED', message: 'Reconnect PayPal.' });
  const m = parseMoney(req.body || {}, res); if (!m) return;
  try {
    const result = await paypal.createPaymentRequest({
      payeeEmail: pp.e, amount: m.amount, currency: m.currency, note: req.body.note,
      returnUrl: `${PUBLIC_URL}/pay/done`, cancelUrl: `${PUBLIC_URL}/pay/cancel`, requestId: crypto.randomUUID(),
    });
    res.json({
      id: result.id, approveUrl: result.approveUrl,
      shareText: `Please pay ${m.currency} ${m.amount.toFixed(2)}${req.body.note ? ` for ${req.body.note}` : ''} to ${pp.n || pp.e} with PayPal: ${result.approveUrl}`,
    });
  } catch (err) { fail(res, err); }
});

// The payer (anywhere in the world) lands here after approving on PayPal.
app.get(['/pay/done', '/paypal/paid'], async (req, res) => {
  const orderId = String(req.query.token || '');
  if (!/^[A-Z0-9]{10,30}$/.test(orderId)) return res.status(400).send(page('Unknown payment', 'This payment link is not valid.', { ok: false }));
  try {
    const r = await paypal.captureOrder(orderId);
    res.send(page('Payment sent', `Thank you. ${r.currency} ${r.amount} was paid.`));
  } catch (err) {
    console.error('[capture]', err.message);
    res.status(502).send(page('Payment not completed', 'This payment could not be completed. Nothing was charged.', { ok: false }));
  }
});

app.get(['/pay/cancel', '/paypal/declined'], (_req, res) => res.send(page('Payment cancelled', 'You cancelled. Nothing was charged.', { ok: false })));

// ---------- Shopping (Channel3): find the product and its live price ----------
app.get('/api/products', async (req, res) => {
  const dev = device(req, res); if (!dev) return;
  const q = String(req.query.q || '').trim().slice(0, 120);
  if (q.length < 2) return res.status(400).json({ error: 'QUERY_REQUIRED', message: 'Type what you want to buy.' });
  if (!productsConfigured()) return res.status(503).json({ error: 'PRODUCTS_OFF', message: 'Product search is not configured.' });
  try {
    const maxPrice = Number(req.query.max) > 0 ? Number(req.query.max) : undefined;
    const items = await searchProducts(q, { limit: 6, maxPrice });
    res.json({ query: q, items, kesPerUsd: KES_PER_USD });
  } catch (err) {
    console.error('[channel3]', err.message);
    res.status(502).json({ error: 'PRODUCTS_FAILED', message: 'Product search is unavailable right now.' });
  }
});

// ---------- Status of one of the user's own orders ----------
app.get('/api/paypal/orders/:id', async (req, res) => {
  const dev = device(req, res); if (!dev) return;
  const pp = link(req, res, dev); if (!pp) return;
  try {
    const o = await paypal.getOrder(req.params.id);
    const me = String(pp.e || '').toLowerCase();
    if (![o.payee, o.payer].map((x) => String(x || '').toLowerCase()).includes(me)) return res.status(404).json({ error: 'NOT_FOUND' });
    res.json(o);
  } catch (err) { fail(res, err); }
});

app.listen(PORT, () => console.log(`hushhboard-money listening on ${PORT} (public ${PUBLIC_URL}); PayPal ${paypal.configured() ? 'configured' : 'NOT configured'}`));
