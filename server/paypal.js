// Thin PayPal REST client (sandbox by default). Uses Node's built-in fetch.
// Request shapes verified with the APIMatic PayPal Context Plugin (tools/context-plugins/paypal) and its SDK map
// (Vault: createSetupToken / createPaymentToken / deletePaymentToken; Orders: createOrder / captureOrder / getOrder).
// The client secret lives only here, on the server - never in the app.

export const _fetch = { fn: (...a) => fetch(...a) };
const BASE = process.env.PAYPAL_API_BASE || 'https://api-m.sandbox.paypal.com';
const CLIENT_ID = process.env.PAYPAL_CLIENT_ID || '';
const CLIENT_SECRET = process.env.PAYPAL_CLIENT_SECRET || '';

let cached = { token: null, expiresAt: 0 };

export function configured() {
  return Boolean(CLIENT_ID && CLIENT_SECRET);
}

async function accessToken() {
  if (cached.token && Date.now() < cached.expiresAt - 60_000) return cached.token;
  const res = await _fetch.fn(`${BASE}/v1/oauth2/token`, {
    method: 'POST',
    headers: {
      Authorization: 'Basic ' + Buffer.from(`${CLIENT_ID}:${CLIENT_SECRET}`).toString('base64'),
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    body: 'grant_type=client_credentials',
  });
  const body = await res.json();
  if (!res.ok) throw new PayPalError(res.status, body);
  cached = { token: body.access_token, expiresAt: Date.now() + body.expires_in * 1000 };
  return cached.token;
}

export class PayPalError extends Error {
  constructor(status, body) {
    const issue = body?.details?.[0]?.issue || body?.name || body?.error || 'PAYPAL_ERROR';
    const description = body?.details?.[0]?.description || body?.message || body?.error_description || '';
    super(`${issue}${description ? ': ' + description : ''}`);
    this.status = status;
    this.issue = issue;
    this.body = body;
  }
}

async function call(method, path, body, { requestId } = {}) {
  const token = await accessToken();
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  if (requestId) headers['PayPal-Request-Id'] = requestId;
  const res = await _fetch.fn(`${BASE}${path}`, { method, headers, body: body ? JSON.stringify(body) : undefined });
  if (res.status === 204) return {};
  const text = await res.text();
  const json = text ? JSON.parse(text) : {};
  if (!res.ok) throw new PayPalError(res.status, json);
  return json;
}

const link = (obj, rel) => obj?.links?.find((l) => l.rel === rel)?.href;

/** Step 1 of "Connect PayPal": a setup token the user approves on PayPal's own pages (no purchase). */
export async function createSetupToken({ returnUrl, cancelUrl, requestId }) {
  const body = await call('POST', '/v3/vault/setup-tokens', {
    payment_source: {
      paypal: {
        description: 'HushhBoard: pay with PayPal from your keyboard',
        usage_type: 'MERCHANT',
        customer_type: 'CONSUMER',
        permit_multiple_payment_tokens: true,
        usage_pattern: 'IMMEDIATE',
        experience_context: {
          brand_name: 'HushhBoard',
          return_url: returnUrl,
          cancel_url: cancelUrl,
          shipping_preference: 'NO_SHIPPING',
          vault_instruction: 'ON_PAYER_APPROVAL',
        },
      },
    },
  }, { requestId });
  return { id: body.id, approveUrl: link(body, 'approve'), status: body.status };
}

/** Step 2: turn the approved setup token into a long-lived payment token (no password, no re-login). */
export async function createPaymentToken(setupTokenId) {
  const body = await call('POST', '/v3/vault/payment-tokens', {
    payment_source: { token: { id: setupTokenId, type: 'SETUP_TOKEN' } },
  }, { requestId: `pt-${setupTokenId}` });
  return {
    id: body.id,
    customerId: body.customer?.id,
    email: body.payment_source?.paypal?.email_address || '',
    name: [body.payment_source?.paypal?.name?.given_name, body.payment_source?.paypal?.name?.surname]
      .filter(Boolean).join(' '),
  };
}

export async function deletePaymentToken(id) {
  await call('DELETE', `/v3/vault/payment-tokens/${encodeURIComponent(id)}`);
}

const money = (amount, currency) => ({ currency_code: currency, value: Number(amount).toFixed(2) });

/**
 * Pay someone using the saved PayPal account (no PayPal login again).
 * `payeeEmail` is the receiver's PayPal email.
 */
export async function payWithVault({ vaultId, payeeEmail, amount, currency, note, requestId }) {
  const purchaseUnit = {
    amount: money(amount, currency),
    description: (note || 'Sent with HushhBoard').slice(0, 127),
  };
  if (payeeEmail) purchaseUnit.payee = { email_address: payeeEmail };
  // Saved PayPal (Vault v3 payment token): the documented shape is `payment_source.paypal.vault_id` alone.
  // Do NOT add `stored_credential` here: for PayPal wallets PayPal reads it as a reference to a legacy
  // billing agreement and answers BILLING_AGREEMENT_NOT_FOUND.
  let order;
  try {
    order = await call('POST', '/v2/checkout/orders', {
      intent: 'CAPTURE',
      purchase_units: [purchaseUnit],
      payment_source: { paypal: { vault_id: vaultId } },
    }, { requestId });
  } catch (err) {
    // The saved link no longer exists at PayPal (deleted, revoked, or made with other API credentials).
    if (err instanceof PayPalError && /BILLING_AGREEMENT_NOT_FOUND|INVALID_RESOURCE_ID|RESOURCE_NOT_FOUND|TOKEN_NOT_FOUND|PAYMENT_SOURCE_INFO_CANNOT_BE_VERIFIED/.test(JSON.stringify(err.body || {}) + err.message)) {
      const e = new PayPalError(409, { name: 'PAYPAL_RELINK', message: 'Your saved PayPal link is no longer valid. Connect PayPal again (one login).' });
      e.issue = 'PAYPAL_RELINK';
      throw e;
    }
    throw err;
  }
  if (order.status === 'APPROVED' || order.status === 'CREATED') {
    order = await call('POST', `/v2/checkout/orders/${order.id}/capture`, {}, { requestId: `cap-${order.id}` });
  }
  return summarize(order);
}

/** Ask someone to pay the user: an order whose payee is the user's PayPal; the payer approves on PayPal. */
export async function createPaymentRequest({ payeeEmail, amount, currency, note, returnUrl, cancelUrl, requestId }) {
  const order = await call('POST', '/v2/checkout/orders', {
    intent: 'CAPTURE',
    purchase_units: [{
      amount: money(amount, currency),
      payee: { email_address: payeeEmail },
      description: (note || 'Payment request from HushhBoard').slice(0, 127),
    }],
    payment_source: {
      paypal: {
        experience_context: {
          brand_name: 'HushhBoard',
          user_action: 'PAY_NOW',
          shipping_preference: 'NO_SHIPPING',
          return_url: returnUrl,
          cancel_url: cancelUrl,
        },
      },
    },
  }, { requestId });
  return { id: order.id, status: order.status, approveUrl: link(order, 'payer-action') || link(order, 'approve') };
}

export async function captureOrder(orderId) {
  return summarize(await call('POST', `/v2/checkout/orders/${orderId}/capture`, {}, { requestId: `cap-${orderId}` }));
}

export async function getOrder(orderId) {
  return summarize(await call('GET', `/v2/checkout/orders/${orderId}`));
}

function summarize(order) {
  const pu = order.purchase_units?.[0] || {};
  const capture = pu.payments?.captures?.[0];
  return {
    id: order.id,
    status: capture?.status === 'COMPLETED' ? 'COMPLETED' : order.status,
    amount: capture?.amount?.value || pu.amount?.value,
    currency: capture?.amount?.currency_code || pu.amount?.currency_code,
    payee: pu.payee?.email_address,
    captureId: capture?.id,
    payer: order.payer?.email_address || order.payment_source?.paypal?.email_address,
  };
}
