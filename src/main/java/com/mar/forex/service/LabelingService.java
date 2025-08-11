package com.mar.forex.service;


import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.tribuo.Example;
import org.tribuo.classification.Label;
import org.tribuo.classification.LabelFactory;
import org.tribuo.impl.ArrayExample;
import com.mar.forex.domain.model.Candle;
import com.mar.forex.domain.model.MarketIndicators;

@Service
public class LabelingService {

    public List<Example<Label>> buildExamples(List<Candle> candles,
                                              MarketIndicators ind,
                                              double[] close, double[] high, double[] low,
                                              int warmup, double rr, int H, LabelFactory factory) {
        List<Example<Label>> examples = new ArrayList<>();
        int n = candles.size();
        int upCount = 0, downCount = 0;

        for (int i = warmup; i < n - 1; i++) {
            double maFast = ind.maFast()[i];
            double maSlow = ind.maSlow()[i];
            double rsi = ind.rsi()[i];
            double atr = ind.atr()[i];

            if (Double.isNaN(maFast) || Double.isNaN(maSlow) || Double.isNaN(rsi) || Double.isNaN(atr)) continue;

            double ret1 = (close[i] - close[i - 1]) / close[i - 1];
            double entry = close[i];
            double risk = atr;
            if (risk <= 0 || Double.isNaN(risk) || Double.isInfinite(risk)) continue;

            double longTP = entry + rr * risk;
            double longSL = entry - risk;
            double shortTP = entry - rr * risk;
            double shortSL = entry + risk;

            boolean longWin = false;
            boolean shortWin = false;
            int limit = Math.min(i + H, n - 1);

            for (int j = i + 1; j <= limit; j++) {
                double hi = high[j];
                double lo = low[j];
                if (hi >= longTP && lo > longSL) { longWin = true; break; }
                if (lo <= longSL && hi < longTP) { longWin = false; break; }
            }

            for (int j = i + 1; j <= limit; j++) {
                double hi = high[j];
                double lo = low[j];
                if (lo <= shortTP && hi < shortSL) { shortWin = true; break; }
                if (hi >= shortSL && lo > shortTP) { shortWin = false; break; }
            }

            String y;
            if (longWin && !shortWin) {
                y = "UP";
                upCount++;
            } else if (shortWin && !longWin) {
                y = "DOWN";
                downCount++;
            } else {
                // ambiguous: both long and short unclear, skip this bar
                continue;
            }

            ArrayExample<Label> ex = new ArrayExample<>(factory.generateOutput(y));
            ex.add("maFast", maFast);
            ex.add("maSlow", maSlow);
            ex.add("rsi", rsi);
            ex.add("atr", atr);
            ex.add("ret1", ret1);
            // Engineered features (match TribuoUtil.exampleFromArrays)
            ex.add("maDiff", maFast - maSlow);
            if (i >= 1) ex.add("rsiDelta", rsi - ind.rsi()[i - 1]);
            ex.add("atrNorm", atr / close[i]);
            ex.add("maRatio", maFast / (maSlow + 1e-9));
            if (i >= 5) ex.add("ret5", (close[i] - close[i - 5]) / close[i - 5]);
            // Extra engineered features
            ex.add("maSlopeFast", i >= 1 ? (maFast - ind.maFast()[i-1]) : 0.0);
            ex.add("maSlopeSlow", i >= 1 ? (maSlow - ind.maSlow()[i-1]) : 0.0);
            ex.add("atrRatio", atr / (ind.atr()[i-1] + 1e-9));
            ex.add("rsiNorm", rsi / 100.0);
            if (i >= 10) {
                ex.add("ret10", (close[i] - close[i-10]) / close[i-10]);
            }
            examples.add(ex);
        }
        System.out.printf("Label distribution: UP=%d, DOWN=%d%n", upCount, downCount);
        return examples;
    }
}
