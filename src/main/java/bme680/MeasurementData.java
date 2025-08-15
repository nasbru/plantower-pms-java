package bme680;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public class MeasurementData {
	@JsonProperty("state")
	public String state;
	@JsonProperty("attributes")
	public Attributes attributes;
	
	@JsonCreator
	public MeasurementData(BigDecimal temperature, BigDecimal humidity, BigDecimal pressure, BigDecimal iaq, BigDecimal co2, BigDecimal voc) {
		this.state = String.valueOf(temperature);
		this.attributes = new Attributes(humidity, pressure, iaq, co2, voc);
	}

	static class Attributes {
		@JsonProperty("humidity")
		public BigDecimal humidity;
		@JsonProperty("pressure")
		public BigDecimal pressure;
		@JsonProperty("iaq")
		public BigDecimal iaq;
		@JsonProperty("co2")
		public BigDecimal co2;
		@JsonProperty("voc")
		public BigDecimal voc;
		
		@JsonCreator
		public Attributes(BigDecimal humidity, BigDecimal pressure, BigDecimal iaq, BigDecimal co2, BigDecimal voc) {
			this.humidity = humidity;
			this.pressure = pressure;
			this.iaq = iaq;
			this.co2 = co2;
			this.voc = voc;
		}
	}
}
