package com.github.nasbru.measurements;

import java.math.BigDecimal;

public class IAQ extends Measurement {
	private Accuracy accuracy;
	private Status status;

	public IAQ(int score, int accValue) {
		super(MeasurementType.IAQ, new BigDecimal(score));
		this.accuracy = Accuracy.fromValue(accValue);
		if (score <= 50)
			status = Status.VERY_GOOD;
		else if (score > 50 && score <= 100)
			status = Status.GOOD;
		else if (score > 100 && score <= 200)
			status = Status.AVERAGE;
		else if (score > 200 && score <= 300)
			status = Status.BAD;
		else if (score > 400 && score <= 500)
			status = Status.VERY_BAD;
		else
			status = Status.CRITICAL;
	}
	
	public String getStatus() {
		return status.getValue();
	}

	private enum Accuracy {
		LOW(0), MEDIUM_LOW(1), MEDIUM_HIGH(2), HIGH(3);

		Accuracy(int accValue) {
			this.accValue = accValue;
		}

		int accValue;

		public int getAccValue() {
			return accValue;
		}

		public static Accuracy fromValue(int value) {
			switch (value) {
			case 0:
				return LOW;
			case 1:
				return MEDIUM_LOW;
			case 2:
				return MEDIUM_HIGH;
			case 3:
				return HIGH;
			default:
				throw new IllegalArgumentException("Incorrect value: " + value);
			}
		}
	}

	private enum Status {
		VERY_GOOD("Bardzo dobra"), GOOD("Dobra"), AVERAGE("Umiarkowana"), BAD("Zła"), VERY_BAD("Bardzo zła"),
		CRITICAL("Krytyczna");
		
		
		private Status(String status) {
			this.status = status;
		}
		
		public String getValue() {
			return status;
		}
		public String status;
		
	}

	@Override
	public String toString() {
		String result = String.format("%s: %d (%d)", getType(), getValue().intValue(), accuracy.getAccValue());
		return result;
	}
}