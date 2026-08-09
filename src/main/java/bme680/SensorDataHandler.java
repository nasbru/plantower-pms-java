package bme680;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.ConnectException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import bme680.measurement.Measurement;

public class SensorDataHandler implements SensorListener {
	private static final String SENSOR_ENDPOINT = "/api/states/sensor.bme680";
	private final String fullUrl; 
	private final String haToken; 
	private Logger logger = LoggerFactory.getLogger(SensorDataHandler.class);
	private ObjectMapper objectMapper = new ObjectMapper();
	private HttpClient client = HttpClient.newHttpClient();
	
	public SensorDataHandler(String baseUrl, String haToken) {
		this.fullUrl = baseUrl + SENSOR_ENDPOINT;
		this.haToken = haToken;
		
	}
	@Override
	public void onDataReceived(Measurement[] m) {
		MeasurementData data = new MeasurementData(m[0].getValue(), m[1].getValue(), m[2].getValue(), m[3].getValue(),
				m[4].getValue(), m[5].getValue());
		try {
			String jsonData = objectMapper.writeValueAsString(data);

			// Wysyłanie danych do Home Assistant
			HttpRequest request = HttpRequest.newBuilder().uri(URI.create(fullUrl))
					.header("Authorization", "Bearer " + haToken).header("Content-Type", "application/json")
					.POST(BodyPublishers.ofString(jsonData)).build();

			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

			System.out.println("Response Code: " + response.statusCode());
			System.out.println("Response Body: " + response.body());
		} catch(ConnectException e) {
			logger.warn("No connection with HA host.");
		}
		catch (Exception e) {
			logger.error("Exception during data processing.", e);
		}
	}

}
