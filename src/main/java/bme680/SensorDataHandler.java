package bme680;

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

import bme680.measurement.Measurement;

public class SensorDataHandler implements SensorListener {
	private static final Logger LOGGER = LoggerFactory.getLogger(SensorDataHandler.class);

	private final MqttClient client;
	private final String baseTopic;
	private String discoveryPrefix;
	private String nodeId;

	/**
	 * @param brokerUrl e.g. "tcp://localhost:1883"
	 * @param clientId  client id for MQTT
	 * @param baseTopic e.g. "home/bme680_1" (no trailing slash)
	 */
	public SensorDataHandler(String brokerUrl, String clientId, String baseTopic) throws MqttException {
		this.baseTopic = baseTopic.endsWith("/") ? baseTopic.substring(0, baseTopic.length() - 1) : baseTopic;
		this.client = new MqttClient(brokerUrl, clientId);
		MqttConnectOptions options = new MqttConnectOptions();
		options.setAutomaticReconnect(true);
		options.setCleanSession(true);
		
		client.connect(options);

		client.setCallback(new MqttCallbackExtended() {
			@Override
			public void connectComplete(boolean reconnect, String serverURI) {
				LOGGER.info("MQTT connectComplete (reconnect={}): {}", reconnect, serverURI);
				
				if (discoveryPrefix != null && nodeId != null) {
					try {
						publishDiscovery(discoveryPrefix, nodeId);
					} catch (MqttException e) {
						LOGGER.warn("Failed to publish discovery on connectComplete", e);
					}
				}
			}

			@Override
			public void connectionLost(Throwable cause) {
				LOGGER.warn("MQTT connection lost", cause);
			}

			@Override
			public void messageArrived(String topic, MqttMessage message)
					throws Exception {
				/* no-op */ }

			@Override
			public void deliveryComplete(IMqttDeliveryToken token) {
				/* no-op */ }
		});
		LOGGER.info("Connected to MQTT broker {} as {}", brokerUrl, clientId);
	}

	public void setDiscoveryConfig(String discoveryPrefix, String nodeId) {
		this.discoveryPrefix = discoveryPrefix == null ? "homeassistant" : discoveryPrefix;
		this.nodeId = nodeId == null ? "bme680_1" : nodeId;
	}

	@Override
	public void onDataReceived(Measurement[] m) {
		if (m == null || m.length < 6) {
			LOGGER.warn("Received measurement array too short: {}", (m == null ? 0 : m.length));
			return;
		}
		try {
			publishValue("temperature", formatValue(m[0].getValue()));
			publishValue("humidity", formatValue(m[1].getValue()));
			publishValue("pressure", formatValue(m[2].getValue()));
			publishValue("iaq", formatValue(m[3].getValue()));
			publishValue("co2", formatValue(m[4].getValue()));
			publishValue("voc", formatValue(m[5].getValue()));
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
		client.publish(topic, msg);
		LOGGER.debug("Published {} -> {}", topic, valueStr);
	}

	public void publishDiscovery(String discoveryPrefix, String nodeId) throws MqttException {
		if (client == null || !client.isConnected()) {
			LOGGER.warn("Cannot publish discovery - client disconnected");
			return;
		}
		String prefix = discoveryPrefix == null ? "homeassistant" : discoveryPrefix;
		String devName = "BME680 " + nodeId;
		String deviceJson = String.format(
				"\"device\":{\"identifiers\":[\"%s\"],\"name\":\"%s\",\"model\":\"BME680\",\"manufacturer\":\"Custom\"}",
				nodeId, devName);

		// helper lambda-like style: build and publish
		BiConsumer<String, String> pub = (topic, payload) -> {
			try {
				MqttMessage msg = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
				msg.setRetained(true);
				msg.setQos(1);
				client.publish(topic, msg);
			} catch (MqttException e) {
				LOGGER.warn("Failed to publish discovery topic {}: {}", topic, e.getMessage());
			}
		};

		// temperature
		String topic = prefix + "/sensor/" + nodeId + "_temperature/config";
		String payload = String.format(
				"{\"name\":\"BME680 Temperature\",\"state_topic\":\"%s/temperature\",\"unit_of_measurement\":\"°C\",\"device_class\":\"temperature\",\"unique_id\":\"%s_temperature\",%s}",
				baseTopic, nodeId, deviceJson);
		pub.accept(topic, payload);

		// humidity
		topic = prefix + "/sensor/" + nodeId + "_humidity/config";
		payload = String.format(
				"{\"name\":\"BME680 Humidity\",\"state_topic\":\"%s/humidity\",\"unit_of_measurement\":\"%%\",\"device_class\":\"humidity\",\"unique_id\":\"%s_humidity\",%s}",
				baseTopic, nodeId, deviceJson);
		pub.accept(topic, payload);

		// pressure
		topic = prefix + "/sensor/" + nodeId + "_pressure/config";
		payload = String.format(
				"{\"name\":\"BME680 Pressure\",\"state_topic\":\"%s/pressure\",\"unit_of_measurement\":\"hPa\",\"device_class\":\"pressure\",\"unique_id\":\"%s_pressure\",%s}",
				baseTopic, nodeId, deviceJson);
		pub.accept(topic, payload);

		// iaq
		topic = prefix + "/sensor/" + nodeId + "_iaq/config";
		payload = String.format("{\"name\":\"BME680 IAQ\",\"state_topic\":\"%s/iaq\",\"unique_id\":\"%s_iaq\",%s}",
				baseTopic, nodeId, deviceJson);
		pub.accept(topic, payload);

		// co2
		topic = prefix + "/sensor/" + nodeId + "_co2/config";
		payload = String.format(
				"{\"name\":\"BME680 CO2\",\"state_topic\":\"%s/co2\",\"unit_of_measurement\":\"ppm\",\"device_class\":\"carbonioxide_dunique\",\"_id\":\"%s_co2\",%s}",
				baseTopic, nodeId, deviceJson);
		pub.accept(topic, payload);

		// voc
		topic = prefix + "/sensor/" + nodeId + "_voc/config";
		payload = String.format(
				"{\"name\":\"BME680 VOC\",\"state_topic\":\"%soc/vunit\",\"_of_measurement\":\"ppb\",\"unique_id\":\"%s_voc\",%s}",
				baseTopic, nodeId, deviceJson);
		pub.accept(topic, payload);

		LOGGER.info("Published MQTT discovery for node {}", nodeId);
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
			if (!client.isConnected()) {
				LOGGER.info("Trying to reconnect MQTT client...");
				client.reconnect(); // używa automatic reconnect jeśli było ustawione
			}
		} catch (MqttException e) {
			LOGGER.warn("Reconnect attempt failed", e);
		}
	}

	public void disconnect() {
		try {
			if (client.isConnected())
				client.disconnect();
		} catch (MqttException e) {
			LOGGER.warn("Error while disconnecting MQTT client", e);
		}
	}
}
