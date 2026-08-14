package com.github.nasbru.measurements;

import java.math.BigDecimal;

public class Wind extends Measurement {
    private final Direction direction;

    public Wind(float value, String direction) {
        super(MeasurementType.WIND, new BigDecimal(value));
        this.direction = Direction.valueOf(direction);
    }

    public String getDirection() {
        return direction.name();
    }
    
    @Override
    public String toString() {
        return getType() + ": " + getValue() + getUnit() + " " + direction.name();
    }

    private enum Direction {
        N, NNE, NE, ENE, E, ESE, SE, SSE, S, SSW, SW, WSW, W, WNW, NW, NNW
    }
}