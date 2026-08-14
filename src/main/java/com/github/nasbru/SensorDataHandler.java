package com.github.nasbru;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.nasbru.measurements.Measurement;
import com.github.nasbru.sensors.Sensor;

public class SensorDataHandler implements SensorListener {
	private static final Logger LOGGER = LoggerFactory.getLogger(SensorDataHandler.class);
	private static final String PUB = "_publisher";
	private static final String SUFFIX = "_1";
	private static final String BASE = "home/";
	
	private String discoveryPrefix;
	private String brokerUrl;
	
	private final String nodeId;
	private final String baseTopic;
	private final String clientId;
	
	private MqttClient mqttClient;
	
	private final String sensorName;
	private boolean discoveryPublished = false;

	/**
	 * @param brokerUrl e.g. "tcp://localhost:1883"
	 * @param clientId  client id for MQTT
	 * @param baseTopic e.g. "home/bme680_1" (no trailing slash)
	 * @param sensor    Sensor instance to get measurements from
	 * 
	 * mqtt.bme680.clientId = bme680_publisher_1
		mqtt.bme680.baseTopic = home/bme680_1
		mqtt.bme680.node_id = bme680_1
	 */
	public SensorDataHandler(String sensorName) {
		this.sensorName = sensorName;
		this.nodeId = sensorName + SUFFIX;
		this.baseTopic = BASE + sensorName + SUFFIX;
		this.clientId = sensorName + PUB + SUFFIX;
	}
	
	public void initMqtt() throws MqttException {
		this.mqttClient = new MqttClient(brokerUrl, clientId);
		
		MqttConnectOptions options = new MqttConnectOptions();
		options.setAutomaticReconnect(true);
		options.setCleanSession(true);

		mqttClient.connect(options);

		mqttClient.setCallback(new MqttCallbackExtended() {
			@Override
			public void connectComplete(boolean reconnect, String serverURI) {
				LOGGER.info("MQTT connectComplete (reconnect={}): {}", reconnect, serverURI);
			}

			@Override
			public void connectionLost(Throwable cause) {
				LOGGER.warn("MQTT connection lost", cause);
			}

			@Override
			public void messageArrived(String topic, MqttMessage message) throws Exception {
				/* no-op */ }

			@Override
			public void deliveryComplete(IMqttDeliveryToken token) {
				/* no-op */ }
		});
		LOGGER.info("Connected to MQTT broker {} as {}", brokerUrl, clientId);
	}

	public void setMqttConfig(String brokerUrl, String discoveryPrefix) {
		this.brokerUrl = brokerUrl == null ? "tcp://localhost:1883" : brokerUrl;
		this.discoveryPrefix = discoveryPrefix == null ? "homeassistant" : discoveryPrefix;
	}

	@Override
	public void onDataReceived(Measurement[] m) {
		if (m == null || m.length == 0) {
			LOGGER.warn("Received empty measurement array");
			return;
		}
		
		if(!discoveryPublished) {
			try {
				publishDiscovery(discoveryPrefix, nodeId, m);
				discoveryPublished = true;
			} catch (MqttException e) {
				LOGGER.warn("Failed to publish discovery", e);
			}
		}
		
		try {
			for (Measurement measurement : m) {
				if (measurement != null) {
					String measurementType = measurement.getType().toLowerCase();
					publishValue(measurementType, formatValue(measurement.getValue()));
				}
			}
		} catch (MqttException ex) {
			LOGGER.error("MQTT publish failed", ex);
			tryReconnect();
		} catch (Exception ex) {
			LOGGER.error("Unexpected error while handling sensor data", ex);
		}
	}

	private void publishValue(String suffix, String valueStr) throws MqttException {
		String topic = baseTopic + "/" + suffix;
		MqttMessage msg = new MqttMessage(valueStr.getBytes(StandardCharsets.UTF_8));
		msg.setQos(1);
		msg.setRetained(true);
		mqttClient.publish(topic, msg);
		LOGGER.info("Published {} -> {}", topic, valueStr);
	}

	private void publishDiscovery(String discoveryPrefix, String nodeId, Measurement[] measurements) throws MqttException {
		if (mqttClient == null || !mqttClient.isConnected()) {
			LOGGER.warn("Cannot publish discovery - client disconnected");
			return;
		}
		
		String devName = sensorName + " " + nodeId;
		String deviceJson = String.format(
				"\"device\":{\"identifiers\":[\"%s\"],\"name\":\"%s\",\"model\":\"%s\",\"manufacturer\":\"Custom\"}",
				nodeId, devName, sensorName);

		BiConsumer<String, String> pub = (topic, payload) -> {
			try {
				MqttMessage msg = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
				msg.setRetained(true);
				msg.setQos(1);
				mqttClient.publish(topic, msg);
			} catch (MqttException e) {
				LOGGER.warn("Failed to publish discovery topic {}: {}", topic, e.getMessage());
			}
		};

		// Dynamicznie publikuj discovery dla każdego pomiaru
		
		for (Measurement m : measurements) {
			if (m != null) {
				String type = m.getType().toLowerCase();
				String displayName = capitalizeFirstLetter(m.getType());
				String deviceClass = getDeviceClass(type);
				
				String topic = discoveryPrefix + "/sensor/" + nodeId + "_" + type + "/config";
				String payload = buildDiscoveryPayload(displayName, sensorName, type, nodeId, m, deviceClass, deviceJson);
				pub.accept(topic, payload);
			}
		}

		LOGGER.info("Published MQTT discovery for node {}", nodeId);
	}

	private String buildDiscoveryPayload(String displayName, String sensorName, String measurementType,
										 String nodeId, Measurement measurement, String deviceClass, String deviceJson) {
		String unit = measurement.getUnit();
		
		StringBuilder sb = new StringBuilder();
		sb.append("{\"name\":\"").append(sensorName).append(" ").append(displayName).append("\"");
		sb.append(",\"state_topic\":\"").append(baseTopic).append("/").append(measurementType).append("\"");
		
		if (unit != null && !unit.isEmpty()) {
			sb.append(",\"unit_of_measurement\":\"").append(unit).append("\"");
		}
		
		if (deviceClass != null && !deviceClass.isEmpty()) {
			sb.append(",\"device_class\":\"").append(deviceClass).append("\"");
		}
		
		sb.append(",\"state_class\":\"measurement\"");
		sb.append(",\"unique_id\":\"").append(nodeId).append("_").append(measurementType).append("\"");
		sb.append(",").append(deviceJson);
		sb.append("}");
		
		return sb.toString();
	}

	private String getDeviceClass(String measurementType) {
		return switch(measurementType.toLowerCase()) {
			case "temperature" -> "temperature";
			case "humidity" -> "humidity";
			case "pressure" -> "pressure";
			case "pm1_0", "pm2_5", "pm10" -> "pm25";
			case "co2" -> "carbon_dioxide";
			default -> "";
		};
	}

	private String capitalizeFirstLetter(String str) {
		if (str == null || str.isEmpty()) {
			return str;
		}
		return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
	}

	private String formatValue(BigDecimal value) {
		if (value == null)
			return "0";
		// wymuszenie 2 miejsc po przecinku (np. 25.10). Jeśli nie chcesz, użyj
		// value.toPlainString()
		return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
	}

	private void tryReconnect() {
		try {
			if (!mqttClient.isConnected()) {
				LOGGER.info("Trying to reconnect MQTT client...");
				mqttClient.reconnect();
			}
		} catch (MqttException e) {
			LOGGER.warn("Reconnect attempt failed", e);
		}
	}

	public void disconnect() {
		try {
			if (mqttClient.isConnected())
				mqttClient.disconnect();
		} catch (MqttException e) {
			LOGGER.warn("Error while disconnecting MQTT client", e);
		}
	}
}
