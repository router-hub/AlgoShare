# Requirement Analysis Document: AlgoShare
**Version:** 2.0 (Architecture & Portfolio Update)  
**Date:** January 4, 2026  
**Scope:** A high-frequency, algorithmic crypto trading platform allowing users to automate trades, manage portfolios, and receive AI-driven insights.

---

## 1. Functional Requirements (FR)

### Module 1: Market Data Ingestion Service (`algoshare-ingestion`)
*   **FR-1.1 Real-Time Feed:** Must maintain a persistent WebSocket connection to Binance Public API (`wss://stream.binance.com:9443`) for BTCUSDT and ETHUSDT.
*   **FR-1.2 Normalization:** Parse incoming raw JSON into a standardized `MarketTickEvent` (Symbol, Price, Volume, Timestamp) within 10ms.
*   **FR-1.3 Kafka Production:** Publish events to the `market-data` Kafka topic. Throughput requirement: >1,000 events/sec.

### Module 2: Strategy Service (`algoshare-strategy`)
*   **FR-2.1 Rule Creation:** Users define strategies via UI.
    *   **Logic:** Nested conditions (e.g., `(RSI < 30) AND (PRICE < 50000)`).
    *   **Action:** BUY or SELL.
    *   **Validation:** Service must reject logically impossible rules (e.g., RSI > 100).
*   **FR-2.2 Storage:**
    *   **Cold Storage:** MongoDB/PostgreSQL (JSONB) for saving complex rule definitions.
    *   **Hot Storage (Redis):** Active rules are cached in Redis Sets (`RULES:BTCUSDT`) for O(1) retrieval during tick processing.
*   **FR-2.3 Execution Loop:** Consumes `market-data` from Kafka. If a rule matches, publishes a `SignalEvent` (User, Action, Amount) to Kafka.

### Module 3: Order Execution Service (`algoshare-order`)
*   **FR-3.1 Signal Processing:** Consumes `SignalEvent` from Kafka.
*   **FR-3.2 Two-Phase Fund Locking:**
    1.  **Lock:** Call `algoshare-user-payment` to reserve funds (e.g., hold $100).
    2.  **Execute:** If lock successful, place mock order (or real API call).
    3.  **Commit/Rollback:** If filled, deduct funds permanently. If failed, release lock.
*   **FR-3.3 Portfolio Ledger (New):**
    *   **FR-3.3.1:** Maintain a `portfolio_holdings` table for real-time snapshots (e.g., "User A has 1.5 BTC").
    *   **FR-3.3.2:** Maintain a `trade_history` table for every executed transaction.
    *   **FR-3.3.3:** Calculate "Average Buy Price" on every BUY order to enable P&L tracking.

### Module 4: User & Payment Service (`algoshare-user-payment`)
*   **FR-4.1 Wallet Management:** Maintains user cash balances (USD/USDT).
*   **FR-4.2 Stripe Integration:** Handles PaymentIntent creation and Webhook verification (`payment_intent.succeeded`) to credit funds.
*   **FR-4.3 Atomic Locking:** Must support transactional locking of funds to prevent double-spending during high-frequency trading.

### Module 5: Auth Service (`algoshare-auth`)
*   **FR-5.1 Identity Provider:** Manages User Registration (BCrypt hashing) and Login.
*   **FR-5.2 Token Issuance:** Generates RS256 Signed JWTs containing `user_id` and roles.
*   **FR-5.3 Public Keys:** Exposes a JWK Set (JSON Web Key Set) endpoint for the Gateway to verify signatures.

### Module 6: API Gateway (`algoshare-gateway`)
*   **FR-6.1 Central Entry Point:** All external traffic (Web/Mobile) hits the Gateway.
*   **FR-6.2 Security Guard:** Validates JWT signature before routing to internal services.
*   **FR-6.3 Fault Tolerance:** Implements Circuit Breakers (Resilience4j) for downstream services. Returns a graceful fallback if `algoshare-order` is down.
*   **FR-6.4 Rate Limiting:** Limits AI Chat requests to 10/minute per user via Redis.

### Module 7: AI Financial Assistant (`algoshare-ai`)
*   **FR-7.1 Context-Aware Chat:**
    *   Accepts user query ("Why did my trade fail?").
    *   Fetches recent logs from `algoshare-order` and `algoshare-strategy`.
    *   Sends Context + Query to LLM (OpenAI/Gemini).
*   **FR-7.2 Market Summary:** Can fetch `portfolio_holdings` to give personalized advice ("You are heavy on ETH, maybe diversify?").

---

## 2. Non-Functional Requirements (NFR)

*   **NFR-1 Scalability:** `algoshare-ingestion` and `algoshare-strategy` must be horizontally scalable (stateless).
*   **NFR-2 Latency:** "Tick-to-Signal" latency (Market Data -> Rule Match) must be < 50ms.
*   **NFR-3 Data Consistency:** Wallet balances must strictly adhere to ACID properties. No "Eventual Consistency" for money.
*   **NFR-4 Security:** No service communicates with the outside world directly except the Gateway. Internal communication happens on a private Docker network.
*   **NFR-5 Auditability:** Every financial change (Lock, Deduct, Refund) must have a traceable transaction log.

---

## 3. Data Requirements

### A. Order Service Database (PostgreSQL)
*Responsible for "What did I do?" and "What do I own?"*

| Table                | Critical Columns                                                          | Purpose                                           |
| :------------------- | :------------------------------------------------------------------------ | :------------------------------------------------ |
| `trade_history`      | `id`, `user_id`, `symbol`, `side` (BUY/SELL), `qty`, `price`, `timestamp` | The immutable log of all past actions.            |
| `portfolio_holdings` | `user_id`, `symbol`, `qty`, `avg_buy_price`                               | The current snapshot. One row per Asset per User. |

### B. User Service Database (PostgreSQL)
*Responsible for "How much cash do I have?"*

| Table          | Critical Columns                                         | Purpose                           |
| :------------- | :------------------------------------------------------- | :-------------------------------- |
| `wallets`      | `user_id`, `currency` (USD), `balance`, `locked_balance` | Manages liquid vs. reserved cash. |
| `transactions` | `id`, `type` (DEPOSIT/TRADE), `amount`, `reference_id`   | Audit trail for money movement.   |

### C. Strategy Service Database (MongoDB/Postgres JSONB)
*Responsible for "What are my rules?"*

| Collection/Table | Fields                                                   | Purpose                                 |
| :--------------- | :------------------------------------------------------- | :-------------------------------------- |
| `strategies`     | `_id`, `user_id`, `logic_json`, `status` (ACTIVE/PAUSED) | Stores the complex rule tree structure. |

### D. Redis Cache
*Responsible for "Speed"*

| Key                        | Type    | Purpose                                             |
| :------------------------- | :------ | :-------------------------------------------------- |
| `MARKET:PRICE:{SYMBOL}`    | String  | Latest price for UI/AI.                             |
| `STRATEGY:ACTIVE:{SYMBOL}` | Set     | List of active Strategy IDs to check on every tick. |
| `RATE_LIMIT:{USER_ID}`     | Counter | API throttling for AI service.                      |

---

## 4. System Interfaces & Flows

### External APIs
*   **Binance WebSocket:** Source of Truth for Market Data.
*   **Stripe API:** Payment Processing.
*   **OpenAI/Gemini API:** Intelligence Provider.

### Internal Communication Protocols
*   **Kafka:** High-volume async data (Market Ticks, Strategy Signals).
*   **REST (Internal):** Synchronous control calls (Gateway -> Auth).
*   **Feign/WebClient:** Service-to-Service sync calls (Order -> User for locking funds).

---

## 5. Next Steps for Development

1.  **Phase 1 (Skeleton):** Set up `algoshare-gateway` and `algoshare-auth` to handle logins.
2.  **Phase 2 (The Loop):** Implement Ingestion -> Kafka -> Strategy -> Console Log (Prove data flow).
3.  **Phase 3 (Money):** Implement User Wallet + Stripe + Order Service (Fund Locking).
4.  **Phase 4 (UI):** Build React Dashboard with WebSockets.
5.  **Phase 5 (AI):** Add the Chatbot.
