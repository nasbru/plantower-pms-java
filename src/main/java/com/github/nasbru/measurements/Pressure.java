package com.github.nasbru.measurements;

import java.math.BigDecimal;

public class Pressure extends Measurement{
    public Pressure(float value){
        super(MeasurementType.PRESSURE, new BigDecimal(value));
    }
}