package com.mar.forex.domain.model;

import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * A Live
 *
 * @author Matthew Reichert
 */
@Data
public class Live {
    private boolean enabled;
    @Min(1) private int pollSeconds;
}
