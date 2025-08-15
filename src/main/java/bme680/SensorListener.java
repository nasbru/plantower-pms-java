package bme680;
import bme680.measurement.*;

public interface SensorListener{
    void onDataReceived(Measurement[] measurement);
}
