BUILD REQUEST

Build a production-quality Android application called:

Solana Signal

The application is a real-time Solana memecoin signal bot.

Its purpose is:

Detect → Analyze → Score → Alert → Open Photon

The application itself does NOT execute trades.

The user manually decides whether to buy or sell after receiving a signal.

---

1. CORE CONCEPT

The final product must work like this:

                    PumpPortal
                       │
                       │ WebSocket
                       ▼
              Android Signal Engine
                       │
              ┌────────┴────────┐
              │                 │
        Token Discovery     Trade Streams
              │                 │
              └────────┬────────┘
                       ▼
               Metrics Engine
                       ▼
                Safety Engine
                       ▼
               Momentum Score
                       ▼
                Signal Engine
                       ▼
              Instant Android Alert
                       │
             ┌─────────┴─────────┐
             ▼                   ▼
           BUY                  SELL
             │                   │
             └─────────┬─────────┘
                       ▼
                    PHOTON

The app is NOT a trading bot that signs or broadcasts transactions.

It is a signal generation and alert application.

---

2. ABSOLUTE TRADING RESTRICTION

The Android application MUST NOT:

- hold a Solana private key
- generate a trading wallet
- import a private key
- sign Solana transactions
- broadcast transactions
- call PumpPortal Lightning Trading API
- call PumpPortal Local Transaction API
- execute Jupiter swaps
- execute Raydium swaps
- automatically buy tokens
- automatically sell tokens
- automatically manage a live position

There must be NO automatic trading functionality in this version.

The application only provides information and opens Photon for the user.

The actual trade is performed manually by the user inside Photon.

---

3. PUMPPORTAL AS DATA SOURCE

Use PumpPortal as the primary real-time data source.

Use only the CURRENT official PumpPortal documentation as the source of truth.

Official documentation:

https://pumpportal.fun/data-api/real-time/

Official FAQ:

https://pumpportal.fun/FAQ/

Official fees:

https://pumpportal.fun/fees/

DO NOT invent:

- endpoints
- WebSocket methods
- fields
- event structures
- undocumented APIs
- program IDs
- transaction formats

If the current official documentation changes, adapt the implementation.

---

4. PUMPPORTAL WEBSOCKET

Use the official WebSocket:

wss://pumpportal.fun/api/data?api-key=YOUR_API_KEY

Use ONE persistent WebSocket connection.

NEVER create a new WebSocket connection for every token.

PumpPortal explicitly recommends using one connection and sending additional subscription messages through that connection.

---

5. SUPPORTED SUBSCRIPTIONS

Implement the official PumpPortal subscription methods.

New tokens

{
  "method": "subscribeNewToken"
}

Use this as the primary token discovery stream.

---

Migration

{
  "method": "subscribeMigration"
}

Use this to detect token migration events.

---

Token trades

{
  "method": "subscribeTokenTrade",
  "keys": ["TOKEN_MINT"]
}

Subscribe to individual tokens only after they pass the initial discovery criteria.

---

Account trades

Support:

{
  "method": "subscribeAccountTrade",
  "keys": ["ACCOUNT_ADDRESS"]
}

This is optional in the first release and can later be used for creator/wallet monitoring.

---

Unsubscribe

Implement the documented:

unsubscribeNewToken
unsubscribeMigration
unsubscribeTokenTrade
unsubscribeAccountTrade

Use the exact official message format.

Do not invent alternatives.

---

6. PUMPPORTAL API KEY

The API key must never be hardcoded.

Provide:

Settings → PumpPortal API Key

Store it securely.

Use Android Keystore / encrypted local storage where appropriate.

Never:

- commit it to Git
- print it to logs
- expose it in crash reports
- display it unnecessarily
- send it to an external server

Important:

According to the current PumpPortal documentation, "subscribeNewToken" and "subscribeMigration" are free, while "subscribeTokenTrade" and "subscribeAccountTrade" are metered at 0.01 SOL per 10,000 streamed events and require an API key.

The app must display an estimated data-stream usage indicator if possible.

---

7. WEBSOCKET CONNECTION MANAGER

Create:

PumpPortalWebSocketManager

Responsibilities:

- connect
- authenticate through the API-key URL
- subscribe
- unsubscribe
- reconnect
- restore subscriptions
- monitor connection
- detect stale connection
- measure latency
- handle malformed messages
- handle unknown messages
- prevent duplicate subscriptions

States:

CONNECTING
CONNECTED
DEGRADED
RECONNECTING
DISCONNECTED

---

8. RATE LIMIT PROTECTION

Respect the current PumpPortal limits.

The current FAQ states:

- trading/other endpoints are limited to 25 requests/sec
- WebSocket subscription messages should not exceed 200/sec
- no more than 5000 addresses in a single subscription message

Do not exceed these limits.

Implement:

- subscription queue
- batching
- deduplication
- backpressure
- cooldown
- retry
- exponential reconnect

---

9. TOKEN DISCOVERY

When "subscribeNewToken" produces a new token event:

1. Parse the event.
2. Validate available fields.
3. Normalize the event.
4. Create/update local token record.
5. Calculate token age.
6. Apply initial filters.
7. If eligible, subscribe to its trade stream.
8. Start tracking.

Do not assume fields that are not present.

Create a normalized internal model.

Example:

Token
 ├─ mint
 ├─ name
 ├─ symbol
 ├─ creator
 ├─ uri
 ├─ createdAt
 ├─ firstSeenAt
 ├─ marketCap
 ├─ liquidity
 ├─ price
 ├─ lifecycle
 └─ source

Only populate values actually available from the source.

---

10. INITIAL FILTERS

The default strategy is designed to identify very early momentum.

Default:

MAX_TOKEN_AGE = 300 seconds

MIN_MARKET_CAP = $10,000

BUYERS > SELLERS

BUY_VOLUME > SELL_VOLUME

These must be configurable.

The app must NOT describe these rules as predicting future pumps.

They are simply predefined momentum filters.

---

11. TRADE TRACKING

For every token passing the initial filter:

Subscribe to:

subscribeTokenTrade

Track incoming trades.

For each tracked token maintain rolling windows:

30 seconds
1 minute
3 minutes
5 minutes

Calculate:

- total trades
- buys
- sells
- unique buyers
- unique sellers
- buy volume
- sell volume
- total volume
- average buy size
- average sell size
- largest buy
- largest sell
- latest price
- price change
- volume velocity
- buyer velocity
- seller velocity

---

12. DUPLICATE PROTECTION

Never process the same trade twice.

Use the event's actual available identifier/signature when provided.

If no reliable unique identifier exists:

Do not invent one.

Use a documented deterministic fallback based only on available event data.

Document this behavior.

---

13. BUYER / SELLER METRIC

Calculate:

buyerSellerRatio =
buyers / max(sellers, 1)

Display:

Buyers
Sellers
Ratio

Example:

47 buyers
29 sellers
1.62x

Do not hide the raw numbers.

---

14. BUY / SELL VOLUME

Calculate:

buySellRatio =
buyVolume / max(sellVolume, 1)

Example:

Buy Volume: $4,500
Sell Volume: $1,700
Ratio: 2.65x

---

15. VOLUME VELOCITY

Calculate acceleration using comparable rolling windows.

Example:

current 1m volume
/
previous 1m volume

Possible display:

Volume Velocity: 3.4x

If insufficient history exists:

Velocity: N/A

Never fabricate data.

---

16. PRICE MOMENTUM

Calculate:

30s price change
1m price change
3m price change
5m price change

Use this to identify:

- positive momentum
- accelerating momentum
- declining momentum
- extreme extension

Do not blindly reward extremely large price increases.

A token that has already moved dramatically should not automatically receive a higher score.

---

17. MARKET CAP

Use market-cap information only when reliably provided by the configured data source.

If the source provides the required fields to calculate market cap:

document the formula.

Otherwise:

MARKET_CAP = UNKNOWN

Do not invent a value.

---

18. LIQUIDITY

Track liquidity only when reliable data is available.

If unavailable:

LIQUIDITY = UNKNOWN

The UI must distinguish:

Known
Unknown
Stale

Never display fake liquidity.

---

19. SAFETY ENGINE

Create:

SafetyEngine

It must evaluate only properties that can actually be verified from available data.

Potential checks:

- creator activity
- creator selling
- unusual trading behavior
- abnormal volume
- migration state
- holder concentration when reliable data exists
- liquidity conditions when reliable data exists
- suspicious transaction patterns

Every check must contain:

check
status
reason
timestamp
source

Possible statuses:

PASS
WARN
FAIL
UNKNOWN

If the app cannot verify something:

Use:

UNKNOWN

Never claim that a token is safe merely because no known problem was observed.

---

20. MOMENTUM SCORE

Create an explainable score from:

0–100

Default weighting:

Buyer Pressure        25%
Volume Pressure       25%
Volume Velocity       15%
Price Momentum        10%
Liquidity             10%
Holder Distribution   10%
Safety                 5%

If a component is unavailable, do not silently substitute fake data.

Use a documented handling method for unavailable components.

Display the score breakdown.

Example:

TOTAL SCORE: 86/100

Buyer Pressure:      92
Volume Pressure:     88
Volume Velocity:     81
Price Momentum:      76
Liquidity:           83
Holder Distribution: 78
Safety:             100

---

21. SIGNAL CLASSIFICATION

Default:

0–69     REJECTED

70–79    WATCH

80–100   BUY CANDIDATE

Make thresholds configurable.

The signal must always include reasons.

Example:

🚨 BUY CANDIDATE

$ABC

Score: 86/100

Age: 2m 18s
MC: $18.4K
Liquidity: $7.8K

Buyers: 47
Sellers: 29

Buy/Sell Volume: 2.71x
Volume Velocity: 3.4x
Price Momentum: +18.4%

Safety:
PASS

---

22. SIGNAL QUALITY

Do not generate a signal solely because the score exceeds a threshold.

Require the configured hard filters to pass.

Example:

Age <= 5 minutes
Market Cap >= $10,000
Buyers > Sellers
Buy Volume > Sell Volume
Minimum Score >= 80
Required safety checks not FAILED

Make every hard filter visible.

Example:

FILTERS

Age             PASS
Market Cap      PASS
Buyers>Sellers  PASS
Buy Volume      PASS
Safety          PASS
Score           PASS

SIGNAL = BUY CANDIDATE

---

23. SELL SIGNALS

The app must also generate sell alerts based on configurable conditions.

Possible triggers:

- momentum deterioration
- sell volume acceleration
- buyers/sellers reversal
- price momentum reversal
- safety failure
- large creator selling
- configurable trailing decline
- configurable take-profit threshold
- configurable stop-loss threshold

IMPORTANT:

These are alerts only.

The app does not execute the sell.

Example:

🔴 SELL SIGNAL

$ABC

Reason:
Sell pressure increased

Buyers: 41
Sellers: 58

Buy/Sell Volume: 0.72x

Momentum: weakening

Score:
86 → 61

ACTION:
Review token in Photon

---

24. SIGNAL DEDUPLICATION

Do not spam the user.

Implement:

signalCooldown
signalFingerprint
tokenSignalState

Example:

A token should not generate the same BUY notification every second.

Allow configurable:

BUY SIGNAL COOLDOWN
SELL SIGNAL COOLDOWN
RE-SIGNAL THRESHOLD

Example:

BUY notification only when:
score crosses 80
OR
score increases significantly
OR
a major state change occurs

---

25. ANDROID BACKGROUND SCANNING

This is critical.

The app must continue monitoring when the user leaves the app.

Use an Android Foreground Service for continuous scanning where Android policies and platform behavior require it.

When active, display a persistent Android notification such as:

Solana Signal
Scanning PumpPortal...
Tokens tracked: 37
Connection: Connected

The user must be able to:

START SCANNER
STOP SCANNER

from the application.

Do not falsely claim that the scanner is active if Android has stopped it.

---

26. BATTERY MODES

Provide:

PERFORMANCE
BALANCED
BATTERY SAVER

Performance:

- maximum tracking
- fastest refresh
- maximum active subscriptions within limits

Balanced:

- normal tracking

Battery Saver:

- reduce active tracking where possible

Always show the active mode.

---

27. BACKGROUND NOTIFICATIONS

The application must send Android notifications even when:

- the app is minimized
- the user is using another app
- the screen is off

Subject to Android OS notification and background restrictions.

Notification channels:

BUY SIGNALS
SELL SIGNALS
SAFETY ALERTS
SYSTEM ALERTS

Allow the user to configure them independently.

---

28. HIGH-PRIORITY SIGNAL NOTIFICATION

BUY candidate notification:

🚨 NEW SOLANA SIGNAL

$ABC

Age: 2m 18s
MC: $18.4K

Buyers: 47
Sellers: 29

Buy/Sell: 2.71x
Velocity: 3.4x

Momentum: +18.4%

Score: 86/100
Safety: PASS

PAPER / SIGNAL ONLY

The notification should contain action buttons where Android permits:

VIEW
OPEN PHOTON

---

29. SELL NOTIFICATION

Example:

🔴 SELL SIGNAL

$ABC

Score: 61/100

Sell pressure rising

Buyers: 41
Sellers: 58

Buy/Sell: 0.72x

Momentum: Weakening

Review in Photon

Action:

OPEN PHOTON

---

30. PHOTON INTEGRATION

The app does NOT trade through Photon APIs.

It simply opens Photon for manual trading.

Use the official Photon Solana web terminal:

https://photon-sol.tinyastro.io/

The app should associate every signal with its token mint/contract address.

When the user presses:

OPEN PHOTON

attempt to open the token in the official Photon Solana web interface using ONLY a URL/deep-link format that is verified from current Photon documentation or official behavior.

DO NOT invent a deep-link URL format.

If a token-specific deep link cannot be officially verified:

1. Open the official Photon Solana website.
2. Copy the token mint to the clipboard.
3. Show:
   "Token address copied — paste it into Photon."

Do not send the user to an unverified Photon domain.

Never open:

- lookalike Photon domains
- third-party trading sites
- unknown redirectors

Only use the verified official Photon domain under:

tinyastro.io

The current official Solana terminal is:

photon-sol.tinyastro.io

---

31. BUY BUTTON

Inside the signal details screen:

[ OPEN PHOTON ]

The app should NOT label this:

"BUY NOW"

because the app itself does not buy.

Use:

OPEN IN PHOTON

The user makes the actual decision and executes the trade manually.

---

32. SELL BUTTON

For sell signals:

[ OPEN IN PHOTON ]

The app passes/selects the relevant token context if an officially supported method exists.

Otherwise:

- open Photon
- copy mint to clipboard
- display token address

---

33. TOKEN DETAILS SCREEN

Display:

TOKEN

Name
Symbol
Mint
Creator

Age
Market Cap
Liquidity
Price

BUYERS
SELLERS

BUY VOLUME
SELL VOLUME

BUY/SELL RATIO
VOLUME VELOCITY

30s Momentum
1m Momentum
3m Momentum
5m Momentum

Safety

Score

Also show:

WHY THIS SIGNAL?

Example:

✓ Token younger than 5 minutes
✓ Market cap above minimum
✓ Buyers > Sellers
✓ Buy volume > Sell volume
✓ Volume accelerating
✓ Positive momentum
✓ Required safety checks passed

---

34. LIVE SCANNER SCREEN

Create a fast real-time list.

Each token card:

$ABC

Age       2m 18s
MC        $18.4K
Liquidity $7.8K

B/S       47 / 29
Volume    2.71x
Velocity  3.4x

Score     86

STATUS
BUY CANDIDATE

Use color-coded states, but keep the UI readable and accessible.

---

35. DASHBOARD

Display:

SCANNER
CONNECTED

PumpPortal
CONNECTED

Tokens discovered
Tokens tracked
Signals today
BUY signals
SELL signals

Average score
Highest score

WebSocket latency
Last event

Current subscriptions

---

36. SIGNAL HISTORY

Store:

timestamp
token
mint
signal type
score
metrics
reasons
market cap
liquidity
buyers
sellers
buy volume
sell volume
price

Allow filtering:

BUY
SELL
WATCH
REJECTED

---

37. SIGNAL PERFORMANCE ANALYTICS

This is optional but highly recommended.

Do NOT call this "profit" unless an actual trade was manually recorded.

Instead track:

Signal Outcome

For each signal, calculate hypothetical performance from the signal price:

+5%
+10%
-10%
etc.

Clearly label:

HYPOTHETICAL

Never present hypothetical returns as actual trading profits.

This allows testing whether the signal system has predictive value.

---

38. LOCAL DATABASE

Use:

Room / SQLite

Tables:

tokens
trades
metrics
scores
signals
signal_outcomes
system_events
settings

Store timestamps in UTC.

Indexes:

mint
timestamp
signalType
score

Implement retention settings.

Example:

7 days
30 days
90 days
Unlimited

---

39. APP ARCHITECTURE

Use Kotlin + Jetpack Compose.

Recommended:

Android
 ├── UI
 ├── ViewModels
 ├── Domain
 │    ├── Scanner
 │    ├── Metrics
 │    ├── Scoring
 │    ├── Safety
 │    └── Signals
 │
 ├── Data
 │    ├── PumpPortal
 │    ├── Room
 │    └── Settings
 │
 ├── ForegroundService
 ├── Notifications
 └── PhotonLauncher

Keep PumpPortal-specific code isolated.

Example:

PumpPortalWebSocketManager
PumpPortalEventParser
PumpPortalRepository

The rest of the application should consume normalized internal events.

---

40. NORMALIZED EVENTS

Create internal models:

NormalizedTokenCreatedEvent

NormalizedMigrationEvent

NormalizedTradeEvent

Pipeline:

PumpPortal raw event
        ↓
Parser
        ↓
Validation
        ↓
Normalized event
        ↓
Metrics
        ↓
Scoring
        ↓
Signal

This protects the rest of the application from PumpPortal API changes.

---

41. UNKNOWN DATA POLICY

If data is unavailable:

DO NOT fabricate it.

Use:

UNKNOWN
N/A
INSUFFICIENT DATA

Examples:

Liquidity: UNKNOWN

Holder Distribution: UNKNOWN

Velocity: INSUFFICIENT DATA

The UI must clearly distinguish unavailable information from a value of zero.

---

42. ERROR HANDLING

Handle:

- malformed JSON
- connection timeout
- WebSocket disconnect
- API-key failure
- rate-limit errors
- duplicate events
- missing fields
- unknown event types
- database errors
- Android service termination
- network switching
- stale data

Never crash the scanner because of one malformed event.

---

43. RECONNECTION

When disconnected:

DISCONNECTED
     ↓
RECONNECTING
     ↓
CONNECTED
     ↓
RESTORE SUBSCRIPTIONS

Use exponential backoff.

After reconnect:

1. reconnect WebSocket
2. subscribe to new tokens
3. restore active token subscriptions
4. resume metrics
5. mark stale tokens appropriately

Do not assume missed events were received.

Clearly indicate a data gap when necessary.

---

44. SYSTEM STATUS

Create a system status screen.

Display:

PumpPortal WebSocket
CONNECTED

API Key
CONFIGURED

Last event
0.4 sec ago

Latency
XX ms

Tracked tokens
37

Subscriptions
37

Events/sec
XX

Reconnects
2

Parser errors
0

Database
OK

Foreground service
RUNNING

---

45. SECURITY

The app does not need a trading wallet.

Therefore:

DO NOT implement:

- private-key storage
- seed phrases
- wallet generation
- transaction signing

This significantly reduces security risk.

The only sensitive credential required is the PumpPortal API key.

Protect it appropriately.

---

46. NO BACKEND IN PHASE 1

Do NOT build:

- VPS
- Node.js backend
- PostgreSQL server
- Redis
- cloud scanner
- web dashboard

The first release is:

ANDROID ONLY

All scanning and analysis occurs locally.

---

47. OPTIONAL CLOUD ARCHITECTURE — FUTURE ONLY

Keep the architecture extensible.

Later we may add:

PumpPortal
    ↓
Cloud Scanner
    ↓
Push Notification
    ↓
Android

But this is NOT part of Phase 1.

Do not build it now.

---

48. PERFORMANCE

Optimize for:

- low latency
- low memory
- efficient JSON parsing
- efficient database writes
- minimal UI recomposition
- efficient Flow collection
- subscription management
- notification speed

Do not perform expensive database operations on the main UI thread.

Use Kotlin Coroutines.

---

49. MOCK MODE

Create:

MOCK_MODE = true/false

When enabled:

simulate:

- new token
- trades
- buyers