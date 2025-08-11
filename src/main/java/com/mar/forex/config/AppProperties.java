package com.mar.forex.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;
import com.mar.forex.domain.model.Execution;
import com.mar.forex.domain.model.Filter;
import com.mar.forex.domain.model.Live;
import com.mar.forex.domain.model.MarketData;
import com.mar.forex.domain.model.Paper;
import com.mar.forex.domain.model.Risk;
import com.mar.forex.domain.model.Trading;
import com.mar.forex.domain.model.Training;
import com.mar.forex.infrastructure.broker.Oanda;

@Data
@Validated
@ConfigurationProperties(prefix = "forex")
public class AppProperties {

    @NestedConfigurationProperty
    private Oanda oanda = new Oanda();

    @NestedConfigurationProperty
    private Trading trading = new Trading();

    @NestedConfigurationProperty
    private Live live = new Live();

    @NestedConfigurationProperty
    private Paper paper = new Paper();

    @NestedConfigurationProperty
    private Training training = new Training();

    @NestedConfigurationProperty
    private MarketData marketData = new MarketData();

    @NestedConfigurationProperty
    private Execution execution = new Execution();

    @NestedConfigurationProperty
    private Risk risk = new Risk();

    @NestedConfigurationProperty
    private Filter filter = new Filter();
}
