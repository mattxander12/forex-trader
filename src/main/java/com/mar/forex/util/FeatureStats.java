package com.mar.forex.util;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.DoubleStream;
import org.tribuo.Example;
import org.tribuo.Feature;
import org.tribuo.classification.Label;

@Slf4j
@UtilityClass
public class FeatureStats {

    public void logFeatureStats(List<Example<Label>> examples) {
        if (examples == null || examples.isEmpty()) {
            log.warn("No examples to compute feature stats.");
            return;
        }

        Map<String, List<Double>> featureMap = new HashMap<>();
        for (Example<Label> ex : examples) {
            for (Feature f : ex) {
                featureMap.computeIfAbsent(f.getName(), k -> new ArrayList<>()).add(f.getValue());
            }
        }

        for (Map.Entry<String, List<Double>> e : featureMap.entrySet()) {
            String feat = e.getKey();
            double[] vals = e.getValue().stream().mapToDouble(Double::doubleValue).toArray();
            double min = DoubleStream.of(vals).min().orElse(Double.NaN);
            double max = DoubleStream.of(vals).max().orElse(Double.NaN);
            double mean = DoubleStream.of(vals).average().orElse(Double.NaN);
            double variance = DoubleStream.of(vals).map(v -> Math.pow(v - mean, 2)).average().orElse(Double.NaN);
            double stddev = Math.sqrt(variance);
            log.info("FEATURE {} | min={} max={} mean={} std={}", feat,
                String.format("%.4f", min),
                String.format("%.4f", max),
                String.format("%.4f", mean),
                String.format("%.4f", stddev));
        }
    }
}
