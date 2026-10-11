# Settle — AI Settlement Break Investigator

> Hackathon project. **All data and SWIFT messages are synthetic.** Messages are generated from text templates; BICs, accounts and trades are made up.

## The problem

US equities moved to **T+1** settlement in May 2024. Trades settle one business day after the trade date, and allocations must be affirmed by **9:00 PM ET** on trade date. When a settlement instruction breaks (rejected by the network, unmatched, or pending at the depository), operations has a few hours to fix it, where it used to have a day or more.

**Overnight trading** adds trade-date edge cases. An execution at 22:15 ET belongs to the *next* business day's trade date (8:00 PM ET boundary), which changes both `:98A::TRAD` and `:98A::SETT`.

Investigating a break today means reading raw MT541/MT543/MT548 text, looking up codes, finding the one wrong field, and fixing it by hand. The same mistakes happen again and again, and what was learned fixing them usually isn't written down anywhere.

## What Settle does

1. **Loads trades** from a CSV, assigns trade date / settle date (NY time, 20:00 boundary, T+1, holidays), renders an **MT541** (buy) or **MT543** (sell) from a template and stores the raw message in **Tiger Data** (TimescaleDB).
2. A **mock custodian** validates the message (bad BIC → `NAK T27`) and replies on a scripted timeline with ACK and **MT548** status messages (matched / unmatched / pending / settled). These are also stored raw.
3. For a failed trade, an **LLM agent** (NVIDIA-hosted model via Spring AI) investigates with tools: trade history, a markdown **knowledge wiki**, settlement statistics from a Timescale continuous aggregate, and read-only SQL. It returns a structured root cause and **at most one field fix**.
4. A **human approves**. Code (not the LLM) validates and applies the fix, re-renders and resends the message. On **ACK** the fix is **VERIFIED**, and code writes a **recipe** page into the wiki.
5. The next time the same error happens, the agent finds the recipe first and applies the **learned fix**.

## Architecture

```
                 ┌────────────── Spring Boot (Java 21) ───────────────────────────┐
  trades.csv ──► │ TradeLoader ─► TradeDateService ─► TemplateService (MT541/543) │
  (or upload)    │                                        │                       │
                 │                                        ▼                       │
                 │                               MockCustodianService             │
                 │              BIC check ─► NAK T27  │  scenarios.json timeline  │
                 │                                     ▼  (ACK, MT548 ...)        │
                 │                                 LiteParser (regex)             │
                 │                                        │                       │
  Browser UI ◄──►│ REST API                               ▼                       │
  index.html     │   /api/trades           ┌──────────────────────────────┐       │
                 │   /api/investigations ─►│ AgentService (Spring AI)     │       │
                 │   /approve              │  tools: getTrade,            │       │
                 │   /api/metrics          │   getTradeHistory, wikiSearch│       │
                 │                         │   wikiRead, getSettleStats,  │       │
                 │                         │   querySource (read-only)    │       │
                 │                         └───────┬───────────┬──────────┘       │
                 │  InvestigationService:          │           │                  │
                 │  validate fix → apply → resend  │           │                  │
                 │  → ACK? → write recipe ─────────┼──► wiki/  │ (markdown)       │
                 └─────────────────────────────────┼───────────┼──────────────────┘
                                                   │           │
                     NVIDIA API (OpenAI-compatible)◄┘           ▼
                     integrate.api.nvidia.com          Tiger Data (TimescaleDB)
                                                       trade, investigation,
                                                       swift_message  (hypertable)
                                                       trade_event    (hypertable)
                                                       settle_stats   (continuous aggregate)
```

### Guardrails

- The LLM **never writes** to the database or wiki. It only proposes.
- Only three fields are fixable: `95P/PSET`, `95P/BUYR|SELL`, `36B/SETT`. The proposed `oldValue` must equal the current DB value, otherwise the fix is dropped and confidence lowered.
- A human approves every change. A recipe is written only after the corrected message is **ACKed**.
- `querySource` accepts a single `SELECT` only, adds `LIMIT 50`, and runs in a READ ONLY transaction with a 5s timeout (on a read-only role when one is configured).
- If the LLM fails or times out (30s), the API returns `rootCause: "LLM unavailable"` instead of a stack trace.

## How to run

Prerequisites: JDK 21+ and Maven 3.8+, a Tiger Data (TimescaleDB) service, and an NVIDIA API key (build.nvidia.com).

```bash
cd backend
cp .env.example .env      # fill in values; .env is gitignored
mvn spring-boot:run
# open http://localhost:8080
```

| Variable | Purpose |
|---|---|
| `DB_URL` | `jdbc:postgresql://<host>:<port>/tsdb?sslmode=require` |
| `DB_USER`, `DB_PASSWORD` | Tiger Data credentials |
| `DB_READONLY_USER`, `DB_READONLY_PASSWORD` | optional read-only role for the SQL tool (created at startup if permitted) |
| `NVIDIA_API_KEY` | NVIDIA API key |
| `NVIDIA_MODEL` | model with tool calling, default `google/gemma-4-31b-it` |
| `NVIDIA_BASE_URL` | optional, default `https://integrate.api.nvidia.com` (+ `/v1/chat/completions`) |
| `NVIDIA_ENABLE_THINKING` | optional, default `false`. Sent as `chat_template_kwargs.enable_thinking`. With thinking on, the model can spend all of `max_tokens` on reasoning and return `content: null` |

Sampling options (`max-tokens`, `temperature`, `top-p`, penalties, `seed`) are set under `spring.ai.openai.chat.options` in `application.yml`.
| `SENTRY_DSN` | optional |

The schema (`schema.sql`) is idempotent and runs at startup. It creates the tables, hypertables, the `settle_stats` continuous aggregate, and about 2,000 synthetic history rows.

Check that the model makes real tool calls: `GET http://localhost:8080/api/llm/ping` should return `"ok": true`.

The demo clock runs at 1 demo minute = 10 real seconds (`settle.demo-minute-seconds`).

## Demo script

```bash
# 1. Reset: reload 10 synthetic trades and send them all
curl -X POST localhost:8080/api/demo/reset
curl localhost:8080/api/trades            # HCK541000000002 and ...009 -> NAK (bad PSET "DTCYUS3")

# 2. Investigate the first NAK -> fix 95P/PSET DTCYUS3 -> DTCYUS33, recipeUsed = null
curl -X POST localhost:8080/api/investigations -H 'Content-Type: application/json' \
     -d '{"tradeRef":"HCK541000000002"}'

# 3. Approve -> resend -> ACK -> VERIFIED; wiki/recipes/T27-95P-PSET.md is written
curl -X POST localhost:8080/api/investigations/<id>/approve

# 4. Same error on another trade -> the agent uses the learned fix
curl -X POST localhost:8080/api/investigations -H 'Content-Type: application/json' \
     -d '{"tradeRef":"HCK541000000009"}'     # recipeUsed = "recipes/T27-95P-PSET"

# 5. Pending trade (LACK at slow counterparty) -> expected settle time from Timescale stats
curl -X POST localhost:8080/api/investigations -H 'Content-Type: application/json' \
     -d '{"tradeRef":"HCK543000000005"}'

curl localhost:8080/api/metrics           # investigations, verified, learnedFixesUsed, recipes
```

Planted scenarios in `trades.csv`:

| Trade | Scenario |
|---|---|
| `HCK541000000002` | BUY, PSET `DTCYUS3` (7 chars) → NAK T27 |
| `HCK543000000003` | SELL executed 22:15 ET → next trade date |
| `HCK543000000004` | MT548 unmatched, reason `DQUA` |
| `HCK543000000005` | counterparty `BRKBUS33XXX` → pending `LACK`, settles later |
| `HCK541000000009` | bad PSET again → demonstrates the learned fix |

## API

| Method | Path | Description |
|---|---|---|
| POST | `/api/demo/reset` | wipe demo data and recipes, reload `trades.csv`, send all |
| POST | `/api/trades/upload` | multipart CSV (`file`), same load flow |
| GET | `/api/trades` | trades with latest lifecycle status |
| GET | `/api/trades/{ref}/messages` | raw + parsed messages and events, oldest first |
| POST | `/api/investigations` | `{tradeRef}` → run the agent (synchronous) |
| POST | `/api/investigations/{id}/approve` | apply fix, resend, verify, learn |
| GET | `/api/metrics` | `{investigations, verified, learnedFixesUsed, recipes}` |
| GET | `/api/llm/ping` | tool-calling smoke test |

## Tech

Java 21, Spring Boot 3.5, Spring JDBC (no JPA), Spring AI 1.1 (OpenAI starter pointed at NVIDIA's OpenAI-compatible API), Tiger Data / TimescaleDB (hypertables plus a continuous aggregate). No SWIFT libraries: messages come from text templates and are read by a small regex parser.

## Synthetic data notice

All trades, accounts (`HF0012345`), counterparties (`BRKRUS33XXX`, `BRKBUS33XXX`, ...), message headers and history rows are **fictional and synthetic**. All SWIFT messages are **generated from templates** in `backend/src/main/resources/templates`. The wiki content is written from general public SWIFT / ISO 15022 knowledge.

## AI tools used

- **Claude Code**: used during development to write the backend, templates, wiki and docs.
- **NVIDIA-hosted LLM** (via NVIDIA's OpenAI-compatible API and Spring AI): the runtime investigator agent.
