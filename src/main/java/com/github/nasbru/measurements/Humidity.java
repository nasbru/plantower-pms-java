package com.github.nasbru.measurements;

import java.math.BigDecimal;

public class Humidity extends Measurement{
    public Humidity(float value){
        super(MeasurementType.HUMIDITY, new BigDecimal(value));
    }
}