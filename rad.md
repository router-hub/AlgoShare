# Requirement Analysis Document: AlgoShare

**Version**: 1.0  
**Date**: January 4, 2026  
**Scope**: A high-frequency, algorithmic crypto trading platform allowing users to automate trades based on real-time market data.

## 1. Functional Requirements (FR)
These define specific behaviors and functions the system must support.

### Module 1: Market Data Ingestion
*   **FR-1.1 Real-Time Feed**: System must connect to Binance Public WebSocket API (`wss://stream.binance.com:9443`).
*   **FR-1.2 Data Normalization**: Incoming JSON payloads must be parsed into a standardized Java Record `MarketTick` (Symbol, Price, Timestamp) within 10ms of receipt.
*   **FR-1.3 Event Streaming**: Normalized data must be published to a Kafka Topic (`market-data`) immediately. The system must support a throughput of at least 1,000 events/second.

### Module 2: Strategy & Rule Engine
*   **FR-2.1 Rule Definition**: Users must be able to define rules via the UI:
    *   **Condition**: `GREATER_THAN` or `LESS_THAN`.
    *   **Asset**: BTCUSDT, ETHUSDT, etc.
    *   **Trigger Price**: Decimal value (e.g., 95000.50).
    *   **Action**: `BUY` or `SELL`.
*   **FR-2.2 Hot Caching**: Active rules must be loaded into Redis for O(1) access time. They cannot be fetched from PostgreSQL during live evaluation.
*   **FR-2.3 Evaluation Logic**: For every incoming Kafka tick, the system must query Redis. If Tick Price meets Rule Condition, an "Order Event" is generated.

### Module 3: Order Execution & Wallet
*   **FR-3.1 Asynchronous Execution**: Order Events must be pushed to a RabbitMQ Queue (`trade-orders`).
*   **FR-3.2 Validation**: The consumer must verify the user has sufficient "Virtual USDT" balance in PostgreSQL before executing.
*   **FR-3.3 Atomic Transactions**: Balance deduction and Asset credit must occur in a single database transaction.
*   **FR-3.4 Failure Handling**: If an order fails (e.g., database lock), it must be retried 3 times before being moved to a "Dead Letter Queue" (DLQ).

### Module 4: User & Payment Management
*   **FR-4.1 User Accounts**: Users register with Email/Password. Passwords must be hashed (BCrypt).
*   **FR-4.2 Wallet Recharge**: System must integrate Stripe (Test Mode).
*   **FR-4.3 Balance Update**: Wallet balance is updated only upon receipt of a verified `payment_intent.succeeded` Webhook from Stripe.

### Module 5: Frontend Dashboard
*   **FR-5.1 Live Updates**: The React UI must display real-time prices via WebSocket (SockJS/Stomp), not HTTP polling.
*   **FR-5.2 Visual Feedback**: Price changes must trigger visual indicators (Green flash for up, Red flash for down)

### Module 6: AI Financial Assistant (The "Smart" Feature)
This module provides a context-aware chatbot that answers user questions about market trends using real-time data.

*   **FR-6.1 Contextual Inquiry**:
    *   **User Action**: User asks natural language questions (e.g., "Why is ETH dropping?" or "Summarize the market status").
    *   **System Logic (RAG - Retrieval Augmented Generation)**:
        1.  Receive user prompt.
        2.  Fetch current price and 24h trend from Redis (e.g., `PRICE:ETH = 2000, CHANGE = -5%`).
        3.  Inject this data into the System Prompt for the AI Model.
        4.  **Output**: AI generates a response referencing the actual live price, not outdated training data.

*   **FR-6.2 Market Sentiment Analysis (Optional/Advanced)**:
    *   **Requirement**: If the user asks for "News," the system queries an external News API (like CryptoPanic), summarizes the top 3 headlines, and correlates them with the price movement.

*   **FR-6.3 Disclaimer Enforcement**:
    *   **Requirement**: Every AI response containing financial analysis must automatically append a static disclaimer: *"This is AI-generated analysis and not financial advice."*

## 2. Non-Functional Requirements (NFR)
These define the quality attributes of the system.

### Performance & Latency
*   **NFR-1 Latency**: "Tick-to-Dashboard" latency (time from Binance -> User Screen) must be < 200ms.
*   **NFR-2 Throughput**: The Ingestion Service must handle bursts of up to 5,000 ticks/second without crashing.

### Scalability & Reliability
*   **NFR-3 Horizontal Scaling**: The Strategy Service must be stateless to allow Kubernetes to scale pods based on CPU usage.
*   **NFR-4 Data Persistence**: Redis is ephemeral (cache). All user rules and balances must be persisted to PostgreSQL to survive a total system restart.

### Security
*   **NFR-5 API Security**: All REST endpoints (except Login/Register) must be protected via JWT (JSON Web Tokens).
*   **NFR-6 Secret Management**: Stripe Keys and Database Credentials must be injected via Environment Variables, never hardcoded.
*   **NFR-7 AI Latency**: Unlike the trading engine (<200ms), the AI Chat response time is acceptable up to 3-5 seconds.
*   **NFR-8 Rate Limiting**: To manage API costs (OpenAI/Gemini), users are limited to 10 AI queries per minute.

## 3. Data Requirements

### Database Schema (PostgreSQL)

| Table          | Purpose                  | Critical Columns                                                |
| :------------- | :----------------------- | :-------------------------------------------------------------- |
| `users`        | Store identity & balance | `id` (UUID), `email`, `password_hash`, `balance` (BigDecimal)   |
| `strategies`   | Store user rules         | `id`, `user_id`, `asset`, `condition`, `target_price`, `status` |
| `orders`       | Trade history            | `id`, `user_id`, `strategy_id`, `price`, `amount`, `timestamp`  |
| `transactions` | Payment logs             | `id`, `stripe_id`, `amount`, `status`, `created_at`             |

### Cache Schema (Redis)

| Key Pattern      | Type   | Purpose                                                      |
| :--------------- | :----- | :----------------------------------------------------------- |
| `PRICE:{SYMBOL}` | String | Stores latest price (e.g., `PRICE:BTC = "98000"`)            |
| `RULES:{SYMBOL}` | Set    | Stores active rules for that symbol to minimize search time. |

## 4. System Interfaces

### External APIs
*   **Binance WebSocket**: `wss://stream.binance.com:9443/ws/btcusdt@trade`
*   **Stripe API**: `https://api.stripe.com/v1/payment_intents`
*   **LLM Provider**: OpenAI API (GPT-4o-mini) or Google Gemini API (Flash 1.5).

### Internal Communication
*   **Ingestion -> Strategy**: Apache Kafka (Topic: `market-data`)
*   **Strategy -> Execution**: RabbitMQ (Queue: `trade-orders`)
*   **Backend -> Frontend**: WebSocket (Stomp over SockJS)
*   **Chat Service -> Redis**: Fetches live market context before calling the LLM.

## 5. Feasibility Conclusion
*   **Technical Feasibility**: Validated. The combination of Kafka (high throughput) and Redis (low latency) addresses the bottleneck of real-time processing.
*   **Data Feasibility**: Validated. Binance Public API provides sufficient volume for stress testing.
*   **Economic Feasibility**: Validated. All tools (Docker, Spring Boot, Postgres) are open source; Stripe and Binance APIs have free tiers suitable for development.