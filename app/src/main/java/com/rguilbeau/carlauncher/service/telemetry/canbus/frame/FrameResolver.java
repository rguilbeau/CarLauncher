package com.rguilbeau.carlauncher.service.telemetry.canbus.frame;

import com.rguilbeau.carlauncher.service.telemetry.canbus.frame.peugeot407.Frame0B6;
import com.rguilbeau.carlauncher.service.telemetry.canbus.frame.peugeot407.Frame0F6;
import com.rguilbeau.carlauncher.utils.log.CarLog;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Registre statique des décodeurs {@link Frame} disponibles, indexés par {@link Vehicle}.
 * <p>
 * Pour ajouter un décodeur : l'ajouter à la liste du véhicule correspondant dans {@link #REGISTRY}.
 * L'id et le véhicule restent déclarés une seule fois, sur la classe {@link Frame}, via
 * {@link FrameInfo} — {@link #resolve} les relit par réflexion sur les classes déjà connues
 * (simple lecture d'annotation, sans scan de l'APK).
 * </p>
 */
public class FrameResolver {

    /** Tag utilisé pour l'identification des messages de journalisation de cette classe. */
    private static final String TAG = "FrameResolver";

    /** Décodeurs déclarés pour chaque véhicule pris en charge. */
    private static final Map<Vehicle, List<Supplier<Frame>>> REGISTRY = new HashMap<>();

    // Déclaration explicite des décodeurs par véhicule : ajouter ici toute nouvelle trame/tout
    // nouveau véhicule pris en charge.
    static {
        REGISTRY.put(Vehicle.PEUGEOT_407, Arrays.asList(
                Frame0B6::new,
                Frame0F6::new
        ));
    }

    private FrameResolver() {
    }

    /**
     * Construit la table [id de trame CAN -> décodeur] pour le véhicule ciblé.
     *
     * @param targetVehicle Véhicule pour lequel instancier les décodeurs.
     * @return Table des décodeurs indexés par id de trame (hexadécimal majuscule). Vide si aucun
     *         décodeur n'est déclaré pour ce véhicule.
     */
    public static Map<String, Frame> resolve(Vehicle targetVehicle) {
        Map<String, Frame> framesMap = new HashMap<>();

        for (Supplier<Frame> supplier : REGISTRY.getOrDefault(targetVehicle, List.of())) {
            Frame instance = supplier.get();
            FrameInfo info = instance.getClass().getAnnotation(FrameInfo.class);

            if (info == null) {
                CarLog.e(TAG, "Décodeur " + instance.getClass().getSimpleName() + " sans @FrameInfo, ignoré");
                continue;
            }
            if (info.vehicle() != targetVehicle) {
                CarLog.e(TAG, "Décodeur " + instance.getClass().getSimpleName() + " déclaré pour "
                        + targetVehicle + " mais annoté @FrameInfo(vehicle=" + info.vehicle() + "), ignoré");
                continue;
            }
            if (framesMap.containsKey(info.id())) {
                CarLog.e(TAG, "Id de trame CAN dupliqué pour " + targetVehicle + ": " + info.id()
                        + " (" + instance.getClass().getSimpleName() + " écrase le décodeur précédent)");
            }
            framesMap.put(info.id(), instance);
        }

        if (framesMap.isEmpty()) {
            CarLog.e(TAG, "Aucun décodeur de trame CAN déclaré pour " + targetVehicle);
        }

        return framesMap;
    }
}
