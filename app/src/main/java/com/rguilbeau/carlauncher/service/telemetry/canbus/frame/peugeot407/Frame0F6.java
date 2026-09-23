package com.rguilbeau.carlauncher.service.telemetry.canbus.frame.peugeot407;

import com.rguilbeau.carlauncher.service.telemetry.canbus.data.VehicleData;
import com.rguilbeau.carlauncher.service.telemetry.canbus.frame.Frame;
import com.rguilbeau.carlauncher.service.telemetry.canbus.frame.FrameInfo;
import com.rguilbeau.carlauncher.service.telemetry.canbus.frame.Vehicle;

/**
 * Décode la trame CAN 0F6 (bus Confort) de la Peugeot 407 : état du contact (bit 0 du premier
 * octet) et kilométrage total, encodé sur 3 octets (octets 3 à 5).
 */
@FrameInfo(id = "0F6", vehicle = Vehicle.PEUGEOT_407)
public class Frame0F6 implements Frame {

    @Override
    public void parse(String dataHex, VehicleData vehicleData) {
        // Trame plus courte que prévu (bus bruité, DLC inattendu) : on ignore plutôt que de
        // lever une exception d'index.
        if (dataHex.length() < 12) return;

        int byte0 = Integer.parseInt(dataHex.substring(0, 2), 16);
        long byte3 = Long.parseLong(dataHex.substring(6, 8), 16);
        long byte4 = Long.parseLong(dataHex.substring(8, 10), 16);
        long byte5 = Long.parseLong(dataHex.substring(10, 12), 16);

        boolean contactOn = (byte0 & 0x01) == 1;
        long odometer = (byte3 << 16) | (byte4 << 8) | byte5;

        vehicleData.contactOn.set(contactOn);
        vehicleData.odometer.set(odometer);
    }
}
