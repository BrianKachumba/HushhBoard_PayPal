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

test('vaulted payment sends vault_id only (stored_credential makes PayPal look for a billing agreement)', async () => {
  const calls = fakePayPal((url) => url.endsWith('/v2/checkout/orders')
    ? [201, { id: 'O1', status: 'COMPLETED', purchase_units: [{ amount: { value: '5.00', currency_code: 'USD' }, payee: { email_address: 'f@x.com' } }] }]
    : [500, {}]);
  const r = await paypal.payWithVault({ vaultId: 'V', payeeEmail: 'f@x.com', amount: 5, currency: 'USD', requestId: 'r' });
  assert.equal(r.status, 'COMPLETED');
  assert.deepEqual(calls.find((c) => c.url.endsWith('/orders')).body.payment_source, { paypal: { vault_id: 'V' } });
});

test('a vault link PayPal no longer knows asks the user to reconnect', async () => {
  fakePayPal((url) => url.endsWith('/v2/checkout/orders')
    ? [422, { name: 'UNPROCESSABLE_ENTITY', details: [{ issue: 'BILLING_AGREEMENT_NOT_FOUND', description: 'The requested Billing Agreement token was not found.' }] }]
    : [500, {}]);
  await assert.rejects(
    paypal.payWithVault({ vaultId: 'V', payeeEmail: 'f@x.com', amount: 5, currency: 'USD', requestId: 'r2' }),
    (e) => e.issue === 'PAYPAL_RELINK' && e.status === 409,
  );
});
