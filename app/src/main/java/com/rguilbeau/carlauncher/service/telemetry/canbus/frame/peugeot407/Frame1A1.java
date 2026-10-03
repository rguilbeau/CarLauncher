package com.rguilbeau.carlauncher.service.telemetry.canbus.frame.peugeot407;

import com.rguilbeau.carlauncher.service.telemetry.canbus.data.VehicleData;
import com.rguilbeau.carlauncher.service.telemetry.canbus.data.dto.VehicleMessage;
import com.rguilbeau.carlauncher.service.telemetry.canbus.frame.Frame;
import com.rguilbeau.carlauncher.service.telemetry.canbus.frame.FrameInfo;
import com.rguilbeau.carlauncher.service.telemetry.canbus.frame.Vehicle;

import java.util.Optional;

/**
 * Décode la trame CAN 1A1 (bus Confort) de la Peugeot 407 : Message d'information
 * (popup destiné à l'utilisateur) envoyé sur le réseau CAN
 */
@FrameInfo(id = "1A1", vehicle = Vehicle.PEUGEOT_407)
public class Frame1A1 implements Frame {

    @Override
    public void parse(String dataHex, VehicleData vehicleData) {
        // Il faut au moins 3 octets (6 caractères) pour lire le code et le statut d'affichage
        if (dataHex.length() < 6) return;

        // Octet 1 : Code du message
        int byte1 = Integer.parseInt(dataHex.substring(2, 4), 16);

        // Octet 2 : Contient le bit d'affichage Z
        int byte2 = Integer.parseInt(dataHex.substring(4, 6), 16);

        // Le bit de poids fort (0x80) de l'octet 2 vaut 1 lorsqu'il faut afficher le message
        boolean show = (byte2 & 0x80) != 0;

        VehicleMessage message = null;
        if (show) {
            message = resolveMessage(true ,byte1);
        }

        if(message == null) {
            message = new VehicleMessage();
        }

        // Assignation de la valeur
        vehicleData.vehicleMessage.set(message);
    }

    /**
     * Retourne le message en français en fonction du code du byte 1
     *
     * @param visible true si le message doit être affiché, false sinon
     * @param messageCode Le code du message
     * @return Le message en français
     */
    private VehicleMessage resolveMessage(boolean visible, int messageCode) {
        switch (messageCode) {
            case 0x00:
                return new VehicleMessage(
                        VehicleMessage.Level.INFORMATION, visible, "Diagnostic OK"
                );
            case 0x01:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Température moteur trop élevée"
                );
            case 0x03:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Niveau liquide de refroidissement trop bas"
                );
            case 0x04:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Vérifier le niveau d'huile moteur"
                );
            case 0x05:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Pression d'huile moteur trop basse"
                );
            case 0x08:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Système de freinage défaillant"
                );
            case 0x0A:
                return new VehicleMessage(
                        VehicleMessage.Level.INFORMATION, visible, "Suspension pneumatique OK"
                );
//            case 0x0B:
//            Already displayed by the Android Head Unit, ignore this message
//                return new VehicleMessage(
//                        VehicleMessage.Level.WARNING, visible, "Ouvrant ouvert (Porte, coffre ou capot)"
//                );
            case 0x0D:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Crevaison(s) détectée(s)"
                );
            case 0x0F:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Risque de colmatage du filtre à particules"
                );
            case 0x11:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Suspension défaillante : vitesse max 90 km/h"
                );
            case 0x12:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Suspension défaillante"
                );
            case 0x13:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Direction assistée défaillante"
                );
            case 0x14:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Vitesse limitée à 10 km/h !"
                );
            case 0x61:
                return new VehicleMessage(
                        VehicleMessage.Level.INFORMATION, visible, "Frein à main serré"
                );
            case 0x62:
                return new VehicleMessage(
                        VehicleMessage.Level.INFORMATION, visible, "Frein à main desserré"
                );
            case 0x64:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Commande de frein à main défaillante : frein auto activé"
                );
            case 0x67:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Plaquettes de frein usées"
                );
            case 0x68:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Frein à main défaillant"
                );
            case 0x69:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Déflecteur mobile défaillant"
                );
            case 0x6A:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Système de freinage ABS défaillant"
                );
            case 0x6B:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Système ESP / ASR défaillant"
                );
            case 0x6C:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Suspension défaillante"
                );
            case 0x6D:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Direction assistée défaillante"
                );
            case 0x6E:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Boîte de vitesses défaillante"
                );
            case 0x6F:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Régulateur de vitesse défaillant"
                );
            case 0x73:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Capteur de luminosité ambiante défaillant"
                );
            case 0x74:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Ampoule(s) de feu de position défaillante(s)"
                );
            case 0x75:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Réglage automatique des phares défaillant"
                );
            case 0x76:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Phares directionnels défaillants"
                );
            case 0x78:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Défaut Airbag"
                );
            case 0x79:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Capot actif défaillant"
                );
            case 0x7A:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Boîte de vitesses défaillante"
                );
            case 0x7B:
                return new VehicleMessage(
                        VehicleMessage.Level.INFORMATION, visible, "Appuyez sur le frein et placez le levier sur 'N'"
                );
            case 0x7D:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Présence d'eau dans le filtre à gazole"
                );
            case 0x7E:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Système de contrôle moteur défaillant"
                );
            case 0x7F:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Système antipollution défaillant"
                );
            case 0x81:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Niveau d'additif FAP trop bas"
                );
            case 0x83:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Antidémarrage électronique défaillant"
                );
            case 0x86:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Porte latérale droite défaillante"
                );
            case 0x87:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Porte latérale gauche défaillante"
                );
            case 0x89:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Système de mesure de place défaillant"
                );
            case 0x8A:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Charge batterie ou alimentation électrique défaillante"
                );
            case 0x8D:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Pression des pneus trop basse"
                );
            case 0x97:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Alerte de franchissement de ligne défaillante (AFIL)"
                );
            case 0x9D:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Ampoule(s) antibrouillard défaillante(s)"
                );
            case 0x9E:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Clignotant(s) défaillant(s)"
                );
            case 0xA0:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Ampoule(s) de feu de position défaillante(s)"
                );
            case 0xA1:
                return new VehicleMessage(
                        VehicleMessage.Level.INFORMATION, visible, "Feux de stationnement actifs"
                );
            case 0xCD:
                return new VehicleMessage(
                        VehicleMessage.Level.INFORMATION, visible, "Impossible d'activer le régulateur de vitesse, vitesse trop faible"
                );
            case 0xCE:
                return new VehicleMessage(
                        VehicleMessage.Level.INFORMATION, visible, "Impossible d'activer le régulateur de vitesse, saisissez la vitesse"
                );
            case 0xD1:
                return new VehicleMessage(
                        VehicleMessage.Level.INFORMATION, visible, "Capot actif déployé"
                );
            case 0xD2:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Ceintures de sécurité avant non bouclées"
                );
            case 0xD3:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Ceinture arrière droite non bouclée"
                );
            case 0xD7:
                return new VehicleMessage(
                        VehicleMessage.Level.INFORMATION, visible, "Placez la boîte automatique en position 'P'"
                );
            case 0xD8:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Risque de verglas"
                );
            case 0xD9:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Frein à main !"
                );
//            case 0xDE:
//            Already displayed by the Android Head Unit, ignore this message
//                 return new VehicleMessage(
//                        VehicleMessage.Level.WARNING, visible, "Ouvrant ouvert (Porte, coffre ou capot)"
//                );
            case 0xDF:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Niveau de lave-glace faible"
                );
            case 0xE0:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Niveau de carburant faible"
                );
            case 0xE1:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Circuit de carburant désactivé"
                );
            case 0xE3:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Pile de la télécommande usée"
                );
            case 0xE4:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Vérifier et réinitialiser la pression des pneus"
                );
            case 0xE5:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Pression des pneus non surveillée"
                );
            case 0xE7:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Vitesse élevée, vérifiez la pression des pneus"
                );
            case 0xE8:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Pression des pneus trop basse"
                );
            case 0xEA:
                return new VehicleMessage(
                        VehicleMessage.Level.DANGER, visible, "Système de démarrage mains libres défaillant"
                );
            case 0xEB:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Échec du démarrage (consultez le manuel)"
                );
            case 0xEC:
                return new VehicleMessage(
                        VehicleMessage.Level.INFORMATION, visible, "Démarrage prolongé en cours"
                );
            case 0xED:
                return new VehicleMessage(
                        VehicleMessage.Level.WARNING, visible, "Démarrage impossible : déverrouillez la direction"
                );
            case 0xEF:
                return new VehicleMessage(
                        VehicleMessage.Level.INFORMATION, visible, "Télécommande détectée"
                );
            case 0xF0:
                return new VehicleMessage(
                        VehicleMessage.Level.INFORMATION, visible, "Diagnostic en cours..."
                );
            case 0xF1:
                return new VehicleMessage(
                        VehicleMessage.Level.INFORMATION, visible, "Diagnostic terminé"
                );
            case 0xF7:
                return new VehicleMessage(
                        VehicleMessage.Level.INFORMATION, visible, "Ceinture arrière gauche débouclée"
                );
            case 0xF8:
                return new VehicleMessage(
                        VehicleMessage.Level.INFORMATION, visible, "Ceinture arrière centrale débouclée"
                );
            case 0xF9:
                return new VehicleMessage(
                        VehicleMessage.Level.INFORMATION, visible, "Ceinture arrière droite débouclée"
                );
            default:
                return null;
        }
    }
}