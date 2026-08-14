package com.github.nasbru.measurements;

import java.math.BigDecimal;

public class Temperature extends Measurement{
    public Temperature(float value){
        super(MeasurementType.TEMPERATURE, new BigDecimal(value));
    }
}