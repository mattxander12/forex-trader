package com.mar.forex.service;

import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.tribuo.Model;
import org.tribuo.classification.Label;
import com.mar.forex.config.AppProperties;
import com.mar.forex.domain.model.Candle;
import com.mar.forex.infrastructure.broker.OandaClient;
import com.mar.forex.util.Indicators;
import com.mar.forex.util.TribuoUtil;

@Service
public class LiveTradingService {
    private final DataService dataService;
    private final AppProperties props;
    private final OandaClient oanda;
    private final MLService ml;

    private Model<Label> model;

    public LiveTradingService(DataService dataService, AppProperties props, OandaClient oanda, MLService ml) {
        this.dataService = dataService;
        this.props = props;
        this.oanda = oanda;
        this.ml = ml;
    }

    public void loadModel(Resource resource) throws Exception {
        this.model = ml.load(resource.getFile().toPath());
    }

    public void tick(String instrument, String granularity) throws Exception {
        List<Candle> candles = dataService.loadCandles(instrument, granularity, 500);
        int i = candles.size() - 2; // last complete bar
        var ex = TribuoUtil.exampleFromBar(candles, i,
                props.getTrading().getFastSma(), props.getTrading().getSlowSma(), props.getPaper().getAtrPeriod());
        if (ex == null) return;
        Label pred = (Label) model.predict(ex).getOutput();
        boolean longPos = pred.getLabel().equals("UP");

        double[] high = candles.stream().mapToDouble(c->c.high).toArray();
        double[] low = candles.stream().mapToDouble(c->c.low).toArray();
        double[] close = candles.stream().mapToDouble(c->c.close).toArray();
        double[] atr = Indicators.atr(high, low, close, props.getPaper().getAtrPeriod());
        double atrNow = atr[i];
        if (Double.isNaN(atrNow)) return;

        double pip = Indicators.pipSize(instrument);
        double stopDist = 1.5 * atrNow;
        double riskAmount = 10000.0 * 1; // TODO: query account balance
        double pips = stopDist / pip;
        double units = riskAmount / (0.0001 * pips);
        units = Math.min(units, props.getTrading().getUnitsCap());
        if (units < 1000) return;

        Candle last = candles.get(candles.size()-1);
        double entry = last.open;
        Double sl = longPos ? entry - stopDist : entry + stopDist;
        Double tp = longPos ? entry + props.getPaper().getRr()*stopDist : entry - props.getPaper().getRr()*stopDist;

        long signedUnits = longPos ? Math.round(units) : -Math.round(units);
        System.out.printf("Placing %s %d %s @ ~%.5f SL=%.5f TP=%.5f%n", longPos? "BUY":"SELL", signedUnits, instrument, entry, sl, tp);
        // Uncomment to send orders on Practice:
        // String resp = oanda.placeMarketOrder(instrument, signedUnits, sl, tp);
        // System.out.println(resp);
    }
}
