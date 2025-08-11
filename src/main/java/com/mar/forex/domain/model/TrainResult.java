package com.mar.forex.domain.model;

import lombok.Data;
import org.tribuo.Model;
import org.tribuo.classification.Label;
import org.tribuo.evaluation.Evaluation;

@Data
public class TrainResult {
    private Model<Label> model;
    private Evaluation<Label> eval;
}
