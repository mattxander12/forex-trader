package com.mar.forex.application;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import com.mar.forex.config.AppProperties;
import com.mar.forex.service.DataService;
import com.mar.forex.service.MLService;
import com.mar.forex.service.BacktesterService;
import com.mar.forex.service.LiveTradingService;

@Configuration
public class CliRunner {

    @Bean
    CommandLineRunner runner(BacktesterService backtesterService, LiveTradingService live, AppProperties props, DataService data, MLService ml) {
        return args -> {
            if (args.length == 0) {
                System.out.println("Usage: backtest|train|live [--instrument EUR_USD] [--granularity M5] [--years 1]");
                return;
            }
            String cmd = args[0];
            String instrument = getArg(args, "--instrument", props.getTrading().getInstrument());
            String granularity = getArg(args, "--granularity", props.getTrading().getGranularity());
            int years = Integer.parseInt(getArg(args, "--years", "1"));

            switch (cmd) {
                case "backtest" -> { /*backtester.run(instrument, granularity, years);*/}
                case "train" -> {
                    var candles = data.loadCandles(instrument, granularity, Math.min(5000, years*365*24*12));
                    var tr = ml.trainClassifier(candles, instrument,
                            props.getTrading().getFastSma(), props.getTrading().getSlowSma(), props.getPaper().getAtrPeriod());
                    System.out.println(tr.getEval().toString());
                    var path = java.nio.file.Path.of("models", "model.zip");
                    java.nio.file.Files.createDirectories(path.getParent());
                    ml.save(tr.getModel(), path);
                    System.out.println("Saved model to " + path.toAbsolutePath());
                }
                case "live" -> {
                    var modelPath = new FileSystemResource("models/model.zip");
                    if (!modelPath.exists()) {
                        System.err.println("Missing models/model.zip. Run 'train' first.");
                        return;
                    }
                    live.loadModel(modelPath);
                    live.tick(instrument, granularity);
                }
                default -> System.out.println("Unknown command: " + cmd);
            }
        };
    }

    private static String getArg(String[] args, String key, String def) {
        for (int i=0;i<args.length-1;i++) {
            if (args[i].equals(key)) return args[i+1];
        }
        return def;
    }
}
