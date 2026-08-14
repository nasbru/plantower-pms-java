package com.github.nasbru.measurements;

import java.math.BigDecimal;

public class VOC extends Measurement{
    public VOC(float value){
        super(MeasurementType.VOC, new BigDecimal(value));
    }
}