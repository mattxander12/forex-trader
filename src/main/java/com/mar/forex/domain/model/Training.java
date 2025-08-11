package com.mar.forex.domain.model;

import lombok.Data;

@Data
public class Training {
    private Integer years = 1;
    private Double valSplit = 0.2;
    private String model = "SGD";
    private Integer labelH = 10;
}
