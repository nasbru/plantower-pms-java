package com.github.nasbru.sensors;

import com.github.nasbru.measurements.Measurement;

public interface Sensor {
	Measurement[] getMeasurements();
	
	String getName();
}
