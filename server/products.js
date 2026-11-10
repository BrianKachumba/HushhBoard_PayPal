// Channel3 product search: turns "get me the Sony XM5" into real products with live prices and buy links,
// so the keyboard AI can prepare a PayPal payment or PayPal request for the right amount.
const BASE = 'https://api.trychannel3.com';
const KEY = process.env.CHANNEL3_API_KEY || '';

export const productsConfigured = () => Boolean(KEY);

export async function searchProducts(query, { limit = 5, maxPrice } = {}) {
  const body = { query, limit };
  if (maxPrice) body.filters = { price: { max_price: maxPrice } };
  const res = await fetch(`${BASE}/v1/search`, {
    method: 'POST',
    headers: { 'x-api-key': KEY, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(json?.detail || `Channel3 error ${res.status}`);
    err.status = res.status;
    throw err;
  }
  return (json.products || []).map(simplify).filter((p) => p.offer);
}

function simplify(p) {
  const offers = (p.offers || [])
    .filter((o) => o?.price?.price != null)
    .sort((a, b) => (a.availability === 'InStock' ? 0 : 1) - (b.availability === 'InStock' ? 0 : 1) || a.price.price - b.price.price);
  const best = offers[0];
  const image = (p.images || []).find((i) => i.is_main_image) || (p.images || [])[0];
  return {
    id: p.id,
    title: p.title,
    brand: p.brands?.[0]?.name || '',
    image: image?.cleaned_url || image?.url || '',
    offerCount: offers.length,
    offer: best ? {
      price: best.price.price,
      currency: best.price.currency || 'USD',
      store: best.domain,
      url: best.url,
      inStock: best.availability === 'InStock',
    } : null,
  };
}
