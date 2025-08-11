package com.mar.forex.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;
import org.tribuo.Example;
import org.tribuo.Model;
import org.tribuo.MutableDataset;
import org.tribuo.classification.Label;
import org.tribuo.classification.LabelFactory;
import org.tribuo.classification.evaluation.LabelEvaluator;
import org.tribuo.classification.sgd.linear.LogisticRegressionTrainer;
import org.tribuo.evaluation.Evaluation;
import com.mar.forex.config.AppProperties;
import com.mar.forex.domain.model.Candle;
import com.mar.forex.domain.model.MarketIndicators;
import com.mar.forex.domain.model.TrainResult;
import com.mar.forex.util.FeatureStats;

@Service
@RequiredArgsConstructor
@Slf4j
public class MLService {

    private final AppProperties props;
    private final IndicatorCalculator indicatorCalculator;
    private final LabelingService labelingService;
    private final DatasetSplitter datasetSplitter;
    private final CalibrationWriter calibrationWriter;

    public TrainResult trainClassifier(List<Candle> candles,
                                       String instrument,
                                       int fast,
                                       int slow,
                                       int atrPeriod) {
        final int n = candles.size();
        double[] close = candles.stream().mapToDouble(c -> c.close).toArray();
        double[] high  = candles.stream().mapToDouble(c -> c.high).toArray();
        double[] low   = candles.stream().mapToDouble(c -> c.low).toArray();

        String maType = props.getTrading().getMaType();

        // compute indicators
        MarketIndicators indicators = indicatorCalculator.compute(close, high, low, fast, slow, atrPeriod, maType);

        // warmup
        int warmup = Math.max(Math.max(fast, slow), Math.max(14, atrPeriod)) + 1;
        log.info("TRAIN | candles={}, fast={}, slow={}, atrP={}, warmup={}, MA={}, valSplit={}",
            n, fast, slow, atrPeriod, warmup, maType,
            (props.getTraining() != null ? props.getTraining().getValSplit() : 0.2));
        if (n <= warmup + 1) {
            throw new IllegalStateException("Not enough candles after warmup to build training examples. candles=" + n + " warmup=" + warmup);
        }

        // labeling config
        double rr = props.getPaper().getRr();
        int H = props.getTraining().getLabelH();
        log.info("TRAIN | outcome-label horizon H={} bars, RR={}", H, rr);

        LabelFactory factory = new LabelFactory();
        List<Example<Label>> examples = labelingService.buildExamples(
            candles, indicators, close, high, low, warmup, rr, H, factory
        );
        log.info("TRAIN | built {} labeled examples", examples.size());
        FeatureStats.logFeatureStats(examples);
        if (examples.isEmpty()) {
            throw new IllegalStateException("Built 0 examples. Check warmup, feature builder, and label logic.");
        }

        // train/test split
        double valSplit = (props.getTraining() != null && props.getTraining().getValSplit() != null)
            ? props.getTraining().getValSplit()
            : 0.2;
        DatasetSplitter.TrainTestSplit split = datasetSplitter.split(examples, factory, instrument, valSplit, 1L);

        MutableDataset<Label> train = split.train();
        MutableDataset<Label> test  = split.test();
        log.info("TRAIN | train size={} test size={}", train.size(), test.size());

        // train & evaluate
        LogisticRegressionTrainer trainer = new LogisticRegressionTrainer();
        Model<Label> model = trainer.train(train);

        LabelEvaluator evaluator = new LabelEvaluator();
        Evaluation<Label> eval = evaluator.evaluate(model, test);

        TrainResult tr = new TrainResult();
        tr.setModel(model);
        tr.setEval(eval);

        // write calibration tables
        calibrationWriter.writeProbabilityTable(model, test, Path.of("models/calibration.csv"));
        calibrationWriter.writeRegimeTable(model, candles, indicators, split.splitIdx(), Path.of("models/calibration.trade.csv"));
        // Optionally write isotonic calibration if supported
        calibrationWriter.writeIsotonicCalibration(model, test, Path.of("models/calibration.isotonic.csv"));

        return tr;
    }

    // ---- Model save/load using Tribuo's file helpers ----
    public void save(Model<Label> model, Path path) throws java.io.IOException {
        java.nio.file.Files.createDirectories(path.getParent());
        model.serializeToFile(path);
    }

    public Model<Label> load(Path path) throws java.io.IOException, ClassNotFoundException {
        @SuppressWarnings("unchecked")
        Model<Label> model = (Model<Label>) Model.deserializeFromFile(path);
        return model;
    }
}
