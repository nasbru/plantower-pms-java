package com.github.nasbru;
import com.github.nasbru.measurements.Measurement;

public interface SensorListener{
    void onDataReceived(Measurement[] measurement);
}
