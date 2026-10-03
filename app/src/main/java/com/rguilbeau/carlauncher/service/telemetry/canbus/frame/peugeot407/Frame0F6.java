package com.rguilbeau.carlauncher.service.telemetry.canbus.frame.peugeot407;

import com.rguilbeau.carlauncher.service.telemetry.canbus.data.VehicleData;
import com.rguilbeau.carlauncher.service.telemetry.canbus.frame.Frame;
import com.rguilbeau.carlauncher.service.telemetry.canbus.frame.FrameInfo;
import com.rguilbeau.carlauncher.service.telemetry.canbus.frame.Vehicle;

/**
 * Décode la trame CAN 0F6 (bus Confort) de la Peugeot 407 : état du contact (bit 3 de l'octet 0),
 * kilométrage total en dixièmes de km encodé sur 3 octets (octets 2 à 4) et température extérieure en °C (octet 6,
 * {@code T = raw / 2 - 39.5}, arrondi à l'entier).
 */
@FrameInfo(id = "0F6", vehicle = Vehicle.PEUGEOT_407)
public class Frame0F6 implements Frame {

    @Override
    public void parse(String dataHex, VehicleData vehicleData) {
        // Trame plus courte que prévu (bus bruité, DLC inattendu) : on ignore plutôt que de
        // lever une exception d'index.
        if (dataHex.length() < 14) return;

        int byte0 = Integer.parseInt(dataHex.substring(0, 2), 16);

        // Odomètre (octets 2, 3 et 4)
        long byte2 = Long.parseLong(dataHex.substring(4, 6), 16);
        long byte3 = Long.parseLong(dataHex.substring(6, 8), 16);
        long byte4 = Long.parseLong(dataHex.substring(8, 10), 16);

        // Température extérieure (octet 6, index hexadécimaux 12 à 14)
        int byte6 = Integer.parseInt(dataHex.substring(12, 14), 16);

        // Contact (octet 0, bit 3 : 0b00001000)
        boolean contactOn = (byte0 & 0x08) != 0;
        // Odomètre transmis en dixièmes de km (ex: 2035339 = 203533,9 km)
        double odometer = ((byte2 << 16) | (byte3 << 8) | byte4) / 10.0;

        // Application de la formule : round(T/2.0 - 39.5)
        int temperature = (int) Math.round(byte6 / 2.0 - 39.5);

        // Assigne la valeur à la propriété correspondante dans VehicleData
        vehicleData.contactOn.set(contactOn);
        vehicleData.odometer.set(odometer);
        vehicleData.outsideTemperature.set(temperature);
    }
}
