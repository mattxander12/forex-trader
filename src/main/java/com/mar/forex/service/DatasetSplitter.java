package com.mar.forex.service;


import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.springframework.stereotype.Service;
import org.tribuo.Example;
import org.tribuo.MutableDataset;
import org.tribuo.classification.Label;
import org.tribuo.classification.LabelFactory;
import org.tribuo.datasource.ListDataSource;
import org.tribuo.provenance.SimpleDataSourceProvenance;

@Service
public class DatasetSplitter {

    public record TrainTestSplit(MutableDataset<Label> train,
                                 MutableDataset<Label> test,
                                 int splitIdx) {}

    public TrainTestSplit split(List<Example<Label>> examples,
                                LabelFactory factory,
                                String instrument,
                                double valSplit,
                                long seed) {
        Collections.shuffle(examples, new Random(seed));
        int splitIdx = (int) Math.floor(examples.size() * (1.0 - valSplit));
        splitIdx = Math.max(1, Math.min(splitIdx, examples.size() - 1));

        var trainSource = new ListDataSource<>(examples.subList(0, splitIdx),
            factory, new SimpleDataSourceProvenance("fx-" + instrument + "-train", factory));
        var testSource = new ListDataSource<>(examples.subList(splitIdx, examples.size()),
            factory, new SimpleDataSourceProvenance("fx-" + instrument + "-test", factory));

        return new TrainTestSplit(new MutableDataset<>(trainSource), new MutableDataset<>(testSource), splitIdx);
    }
}
