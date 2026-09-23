package com.rguilbeau.carlauncher.service.telemetry.canbus.frame.peugeot407;

import com.rguilbeau.carlauncher.service.telemetry.canbus.data.VehicleData;
import com.rguilbeau.carlauncher.service.telemetry.canbus.frame.Frame;
import com.rguilbeau.carlauncher.service.telemetry.canbus.frame.FrameInfo;
import com.rguilbeau.carlauncher.service.telemetry.canbus.frame.Vehicle;

/**
 * Décode la trame CAN 0B6 (bus Confort) de la Peugeot 407 : régime moteur (RPM) et vitesse
 * véhicule, encodés sur les 4 premiers octets.
 */
@FrameInfo(id = "0B6", vehicle = Vehicle.PEUGEOT_407)
public class Frame0B6 implements Frame {

    @Override
    public void parse(String dataHex, VehicleData vehicleData) {
        // Trame plus courte que prévu (bus bruité, DLC inattendu) : on ignore plutôt que de
        // lever une exception d'index.
        if (dataHex.length() < 8) return;

        int byte0 = Integer.parseInt(dataHex.substring(0, 2), 16);
        int byte1 = Integer.parseInt(dataHex.substring(2, 4), 16);
        int byte2 = Integer.parseInt(dataHex.substring(4, 6), 16);
        int byte3 = Integer.parseInt(dataHex.substring(6, 8), 16);

        int rpm = ((byte0 << 8) | byte1) / 8;
        double speed = ((byte2 << 8) | byte3) / 100.0;

        vehicleData.rpm.set(rpm);
        vehicleData.speed.set(speed);
    }
}
