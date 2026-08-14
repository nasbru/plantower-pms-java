package com.github.nasbru.measurements;

import java.math.BigDecimal;

public class PM1_0 extends Measurement{
    public PM1_0(int value){
        super(MeasurementType.PM1_0, new BigDecimal(value));
    }
}
