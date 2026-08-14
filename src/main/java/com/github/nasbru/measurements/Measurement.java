package com.github.nasbru.measurements;

import java.math.BigDecimal;
import java.math.RoundingMode;

public abstract class Measurement {
	private final MeasurementType type;
	private final BigDecimal value;

	public Measurement(MeasurementType type, BigDecimal value) {
		this.type = type;
		if ((value.compareTo(BigDecimal.ZERO) < 0) && type != MeasurementType.TEMPERATURE)
			throw new IllegalArgumentException( type.getDisplayName() + " cannot be negative: " + value.toString());
		this.value = value.setScale(type.getScale(), RoundingMode.HALF_UP);
	}

	public String getType() {
		return type.getName();
	}

	public BigDecimal getValue() {
		return value;
	}

	public String getUnit() {
		return type.getUnit();
	}

	public int getDailyNorm() {
		if (!hasNorm())
			throw new IllegalStateException("No norm defined for " + type.getName() + ".");
		return type.getNorm().daily();
	}

	public int getAnnualNorm() {
		if (!hasNorm())
			throw new IllegalStateException("No norm defined for " + type.getName() + ".");
		return type.getNorm().annual();
	}

	public int percentValueToNorm() {
		float result = (value.floatValue() / getDailyNorm()) * 100;
		return (int) result;
	}

	@Override
	public String toString() {
		String result = type.getName() + ": " + getValue() + type.getUnit();
		if (hasNorm())
			result += " (" + percentValueToNorm() + "%)";
		return result;
	}

	public boolean hasNorm() {
		return type.hasNorm();
	}
}