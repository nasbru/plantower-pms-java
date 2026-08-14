package com.github.nasbru.measurements;

import java.math.BigDecimal;

/*
 * Equivalent Carbon Dioxide
 * Równowartość dwutlenku węgla
 */

public class CO2 extends Measurement{
    public CO2(int value){
        super(MeasurementType.CO2, new BigDecimal(value));
    }
}