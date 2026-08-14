package com.github.nasbru.measurements;

import java.math.BigDecimal;

public class PM2_5 extends Measurement{
    public PM2_5(int value){
        super(MeasurementType.PM2_5, new BigDecimal(value));
    }
}