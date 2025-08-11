# Forex Trader

Starter for **algorithmic forex trading** with a **Spring Boot** backend (Java), **Tribuo** machine learning (SGD, calibration), and integrated **OANDA Practice/Live** trading client.
- OANDA Practice/Live client
- Feature engineering (SMA, RSI, ATR, plus engineered features: maRatio, maDiff, rsiDelta, etc.)
- ML classifier for next-bar UP/DOWN (SGD, with calibration: isotonic, trade-regime)
- Full backtest + paper-trading engine with results chart + summary
- UI hosted separately at [Forex Trader UI](https://github.com/mattxander12/forex-ui)

> Educational only. Trading involves risk.

## Setup
1) Java 17+ and Gradle (or use `./gradlew` if you add a wrapper).
2) Create an OANDA Practice or Live account and obtain your API token and account ID. Set them in `src/main/resources/application.properties` or via environment variables (`OANDA_API_KEY`, `OANDA_ACCOUNT_ID`).
3) Edit `src/main/resources/application.properties` to customize trading parameters, or set environment variables as needed.

## Build & Run
```bash
# Build
./gradlew clean build  # or: gradle clean build

# Backtest 1y of M5 bars on EUR_USD
java -jar build/libs/forex-trader-0.1.0.jar backtest --instrument EUR_USD --granularity M5 --years 1

# Train model (writes models/model.zip)
java -jar build/libs/forex-trader-0.1.0.jar train --instrument EUR_USD --granularity M5 --years 1

# Paper trade a single tick (Practice; order send commented by default)
java -jar build/libs/forex-trader-0.1.0.jar live --instrument EUR_USD --granularity M5
```

## Gradle Wrapper (local)
If you prefer local builds with the wrapper in your working tree:
```bash
# Requires a local Gradle once:
gradle wrapper --gradle-version 8.9
./gradlew clean build
```
