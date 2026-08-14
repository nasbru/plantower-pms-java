package com.github.nasbru.measurements;

import java.math.BigDecimal;

public class PM10 extends Measurement{
    public PM10(int value){
        super(MeasurementType.PM10, new BigDecimal(value));
    }
}