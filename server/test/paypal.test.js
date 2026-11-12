import { test } from 'node:test';
import assert from 'node:assert/strict';

process.env.PAYPAL_CLIENT_ID = 'id';
process.env.PAYPAL_CLIENT_SECRET = 'secret';
const paypal = await import('../paypal.js');

function fakePayPal(handler) {
  const calls = [];
  paypal._fetch.fn = async (url, opts = {}) => {
    const body = opts.body && !String(opts.body).startsWith('grant_type') ? JSON.parse(opts.body) : null;
    calls.push({ url, body });
    if (url.endsWith('/v1/oauth2/token')) return new Response(JSON.stringify({ access_token: 't', expires_in: 3600 }), { status: 200 });
    const [status, json] = handler(url, body, calls);
    return new Response(JSON.stringify(json), { status });
  };
  return calls;
}

test('setup token never combines user_action with vault_instruction (PayPal rejects it)', async () => {
  const calls = fakePayPal(() => [200, { id: 'ST1', status: 'PAYER_ACTION_REQUIRED', links: [{ rel: 'approve', href: 'https://pp/approve' }] }]);
  const r = await paypal.createSetupToken({ returnUrl: 'https://a/r', cancelUrl: 'https://a/c', requestId: 'x' });
  assert.equal(r.approveUrl, 'https://pp/approve');
  const ctx = calls.find((c) => c.url.includes('setup-tokens')).body.payment_source.paypal.experience_context;
  assert.equal(ctx.vault_instruction, 'ON_PAYER_APPROVAL');
  assert.equal(ctx.user_action, undefined);
});

test('paying a person is an order with them as payee, confirmed on PayPal (no vault_id: a vault only pays its own merchant)', async () => {
  const calls = fakePayPal((url) => url.endsWith('/v2/checkout/orders')
    ? [200, { id: 'O1', status: 'PAYER_ACTION_REQUIRED', links: [{ rel: 'payer-action', href: 'https://pp/checkout' }] }]
    : [500, {}]);
  const r = await paypal.createPaymentRequest({ payeeEmail: 'jane@x.com', amount: 12, currency: 'USD', note: 'logo', returnUrl: 'https://a/pay/done?app=1', cancelUrl: 'https://a/pay/cancel?app=1', requestId: 'r' });
  assert.equal(r.approveUrl, 'https://pp/checkout');
  const body = calls.find((c) => c.url.endsWith('/orders')).body;
  assert.equal(body.purchase_units[0].payee.email_address, 'jane@x.com');
  assert.equal(body.payment_source.paypal.vault_id, undefined);
  assert.equal(body.payment_source.paypal.experience_context.user_action, 'PAY_NOW');
});
