# HushhBoard × PayPal: pay from your keyboard, locally and internationally

**PayPal AI Hackathon 2026 submission.** HushhBoard is an Android keyboard built in Kenya, where mobile money (M-PESA) is how people pay. This build adds PayPal next to M-PESA, Airtel Money and banks. Kenyans can pay locally and internationally from the keyboard they already type on, without switching apps.

> Kenya leads the world in mobile money. HushhBoard brings PayPal into that habit, inside every chat.

## What it does

1. **Set up once.** After the usual keyboard setup, the app asks you to connect your money accounts:
   - your Safaricom or Airtel number
   - any number of bank accounts (Equity, KCB, Co-op, Absa and others)
   - your PayPal account

   **Connect PayPal** opens PayPal's own login. PayPal handles the email, password and 2FA. HushhBoard receives only a sealed link to your PayPal identity (from PayPal Vault), so requests go to the right account and PayPal keeps you signed in. Nobody stores passwords.
2. **Pay from the keyboard.** Tap **Pay** on the keyboard toolbar in any app. The same pay screen opens, with the tabs Phone, Till, Paybill and Bank. Your connected accounts are listed under **Payee**, and PayPal is one of them.
3. **AI fills it in.** Copy a chat message such as *"Hey, send me $40 on PayPal: jane@studio.com for the logo"* and open Pay. HushhBoard's money AI reads it **on the device** and offers **"Pay USD 40 to jane@studio.com on PayPal"**. One tap fills the tab, the receiver, the amount and the PayPal method. You always confirm the payment yourself.
4. **Routing tells you what will happen.** Before you confirm, one line says whether the move is a **Withdrawal** (to your own account), a **Credit** (to someone else) or a **Payment**, and how it completes.

### What happens with each choice

| Pay with | Tab / receiver | What HushhBoard does |
| --- | --- | --- |
| PayPal | Bank → **PayPal email** | **Real PayPal payment** (Orders v2): PayPal's own sheet opens, you confirm (usually already signed in), HushhBoard captures it and shows Paid |
| PayPal | Phone → **your own** M-PESA number | **Withdrawal:** one-tap handoff to the M-PESA app's PayPal withdraw (Global Payments) |
| PayPal | Phone → someone else's M-PESA | **Credit:** built and shown as "ready, waiting for the PayPal × Safaricom payout API" |
| PayPal | Phone → Airtel or Telkom | Not supported (PayPal pays out only to M-PESA in Kenya) |
| PayPal | Till / Paybill | Not supported, with a clear message |
| PayPal | Bank → **your own** bank account | **Withdrawal:** handoff to PayPal's withdraw (Equity's PayPal service also mentioned) |
| PayPal | Bank → someone else's bank account | **Credit:** ready, waiting for a bank payout API (for example Equity Jenga or KCB Buni) |
| M-PESA | Bank → PayPal (your own) | **Top-up:** handoff to the M-PESA app's PayPal top-up |
| M-PESA | Bank → any bank account | Works today through the bank's M-PESA paybill (Equity 247247, KCB 522522…) |
| M-PESA / Airtel | Phone / Till / Paybill | The existing USSD/STK flow, confirmed with your PIN on the operator's screen |

The **"ready, waiting for API"** screens are deliberate. They show partners (PayPal, Safaricom, Equity, KCB, Absa) exactly what goes live as soon as they enable their APIs for HushhBoard. No money moves on those screens, and nothing is faked.

**Request money with PayPal.** On the Home tab, *Request via PayPal* creates a PayPal pay link you can drop into any chat. Your client abroad pays on PayPal, and HushhBoard shows **Paid ✓** in your activity.

**Shop abroad with PayPal (Channel3).** Copy *"can you get me the Sony WH-1000XM5?"* and open Pay. The AI spots the shopping request, and [Channel3](https://trychannel3.com) finds the product across 25,000+ stores with live prices. Pick one and HushhBoard can:
- prepare a **PayPal payment** for exactly that amount (for example to the friend who is buying it),
- create a **PayPal request**, so someone can chip in for it, shared with the store link,
- or open the store.

The same search sits on the Home tab ("Shop abroad with PayPal").

## Where the AI is

- **Google ML Kit Entity Extraction:** an on-device ML model that finds money (with currency), emails, phone numbers and IBANs in free text. It runs offline and the message never leaves the phone.
- **HushhBoard's Kenyan entity layer:** tills, paybills with account numbers, M-PESA formats, Kenyan phone networks and bank names, built on our keyboard NLP library (`lib/nlp`).
- **Intent and routing:** decides who to pay, which rail (PayPal, M-PESA or bank) and whether the move is a withdrawal, a credit or a payment.
- **Shopping intent:** "get me / buy / how much is / nunua…" becomes a Channel3 product search. The AI prepares the PayPal payment or request at the live price.
- **Never auto-pays:** the AI fills in the payment; the user confirms. This is the agentic-commerce pattern with a human in the loop.

## Architecture

```
Keyboard toolbar [Pay] ──► Pay screen (Compose) ──► PayRouter ──► M-PESA/Airtel USSD·STK (existing)
        ▲                       │  ▲                    ├──► Handoff: M-PESA app / PayPal app
  copied chat text ──► MoneyAi ─┘  │                    ├──► "Ready for partner API" screen
  (ML Kit, on-device)              │                    └──► HushhBoard money server ──► PayPal REST (sandbox)
                                   │                          • Vault v3: setup-token → payment-token
  App: Setup "Connect your money" ─┘                          • Orders v2: pay a person, request + capture
       Home: manage accounts, PayPal activity
```

- `server/`: a small, **stateless** Node/Express server. It keeps the PayPal client secret off the phone. The Vault payment-token reference is sealed with AES-256-GCM, bound to the phone's device id and stored on the phone, so the server keeps no database and free hosting restarts lose nothing.
- `app/src/main/kotlin/dev/patrickgold/florisboard/money/`: accounts store, PayPal client, router, the AI layer, and the setup and Home screens.
- `app/src/main/kotlin/dev/patrickgold/florisboard/ussd/UssdConfirmationActivity.kt`: the pay screen, now with PayPal.

## Partner tools used

| Partner | How HushhBoard uses it |
| --- | --- |
| **PayPal** | Vault v3 (connect once; links your PayPal identity without a password), Orders v2 (pay a person after confirming on PayPal, payment requests and capture), all in the sandbox |
| **Render** | The money server runs on Render from `render.yaml` (Blueprint): a free web service with health checks and the secrets in Render's environment |
| **Channel3** | `POST /v1/search` behind our server (`/api/products`) powers "shop abroad with PayPal": product, live price, store, buy link |
| **APIMatic Context Plugin (PayPal)** | The PayPal integration was checked against the plugin's SDK map (Vault and Orders field shapes). That check added `stored_credential` for customer-initiated payments with a saved PayPal and `usage_pattern` on setup tokens. The plugin files are in `tools/context-plugins/paypal` |

## Run it

### 1. Server
```bash
cd server
npm install
PAYPAL_CLIENT_ID=... PAYPAL_CLIENT_SECRET=... CHANNEL3_API_KEY=... PUBLIC_URL=https://your-host npm start
```
Or deploy with one click on Render: **New → Blueprint →** pick this repo (`render.yaml`), then paste your **sandbox** Client ID and Secret, plus your Channel3 API key. In the PayPal developer dashboard, the app needs **Vault** and **Log in with PayPal** enabled.

### 2. App
The CI builds a debug APK on every push to `main` (Actions → latest run → `app-debug` artifact). To build locally:
```bash
./gradlew assembleDebug -PhushhMoneyApi=https://your-server-url
```

### 3. Test (PayPal sandbox)
1. Install the APK. Setup is three taps: **Turn on** (switch HushhBoard on in Settings), **Choose HushhBoard**, **Allow**.
2. On **Connect your money**, add a Safaricom number and a bank, then add **PayPal** and log in with a **sandbox personal account** (developer.paypal.com → Testing Tools → Sandbox Accounts).
3. In any app, tap **Pay** on the keyboard toolbar, choose **Bank → Send to: PayPal**, enter another sandbox account's email and an amount, choose the **PayPal** bar and tap **Pay with PayPal**.
4. Copy *"send me $15 on paypal to <sandbox email>"* and open Pay: the suggestion fills the form.
5. Type *"get me the sony wh-1000xm5"* in a chat and tap the shopping suggestion, or tap the bag key: live prices open over the chat.
6. Try PayPal → your own M-PESA number (withdrawal hand-off; the amount is copied for you) and PayPal → another bank account ("ready for API").

Test logins for judges are in the Devpost submission's testing instructions.

## License, credits and trademarks

- **Code:** Apache License 2.0 (`LICENSE`). HushhBoard is a modified fork of [FlorisBoard](https://github.com/florisboard/florisboard) 0.6.0-alpha02, Copyright The FlorisBoard Contributors. Changed FlorisBoard files say so in their header; everything new (the `money`, `ussd`, `scanner` and `ears` packages, `lib/nlp`, `server/`) is original HushhBoard work under the same licence. HushhBoard is not affiliated with or endorsed by the FlorisBoard project.
- **Third-party parts:** APIMatic PayPal skills (MIT, `tools/context-plugins/paypal/`), dictionary and language data (`tools/nlp/DATA_LICENSES.md`, including Google's Taskmaster under CC BY 4.0), Android libraries listed in the app's open-source licences screen. Details in `NOTICE`.
- **Trademarks:** the HushhBoard name and icon are not licensed for reuse. The only third-party logo in the app is PayPal's (`app/src/main/res/drawable-nodpi/logo_paypal.png`), used to identify PayPal and not covered by the code licence. Banks and mobile-money providers appear as their names with coloured initials and generic phone / bank icons.
