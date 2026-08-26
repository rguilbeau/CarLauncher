package com.rguilbeau.carlauncher.service;

import android.app.Notification;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Service d'écoute des notifications système.
 * Spécialisé dans l'interception des notifications de navigation de Google Maps
 * pour en extraire et diffuser les informations de guidage (temps, distance, ETA, direction).
 */
public class NotificationService extends NotificationListenerService {

    /**
     * Nom de paquet officiel de l'application Google Maps.
     */
    private static final String MAPS_PACKAGE = "com.google.android.apps.maps";

    /**
     * Action de l'Intent de diffusion (Broadcast) utilisé pour envoyer les mises à jour de navigation à l'interface.
     */
    public static final String ACTION_MAPS_UPDATE = "com.rguilbeau.carlauncher.MAPS_UPDATE";

    /**
     * Invoquée par le système lorsqu'une nouvelle notification est publiée ou mise à jour.
     * Filtre les notifications de Google Maps, extrait les données de navigation structurées
     * et diffuse ces informations via un Broadcast Intent.
     *
     * @param sbn L'objet représentant la notification publiée.
     */
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (MAPS_PACKAGE.equals(sbn.getPackageName())) {
            Notification notification = sbn.getNotification();
            Bundle extras = notification.extras;

            if (extras != null) {
                CharSequence titleChars = extras.getCharSequence(Notification.EXTRA_TITLE);
                String title = titleChars != null ? titleChars.toString() : null;

                CharSequence subTextChars = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
                String subText = subTextChars != null ? subTextChars.toString() : null;

                Icon turnIcon = notification.getLargeIcon();

                if (subText != null && title != null) {
                    String[] parts = subText.split(" · ");

                    if (parts.length >= 3) {
                        String timeRemaining = parts[0].trim();
                        String distanceRemaining = parts[1].trim();
                        String eta = parts[2].trim();

                        eta = eta.replaceAll("GMT\\+\\d+", "")
                                .replaceAll("Arrivée\\s*:", "")
                                .replace("ETA", "")
                                .trim();

                        Intent intent = new Intent(ACTION_MAPS_UPDATE);
                        intent.putExtra("is_navigating", true);
                        intent.putExtra("time_remaining", timeRemaining);
                        intent.putExtra("distance_remaining", distanceRemaining);
                        intent.putExtra("eta", eta);
                        intent.putExtra("next_direction_distance", title);
                        intent.putExtra("turn_icon", turnIcon);

                        sendBroadcast(intent);
                    }
                }
            }
        }
    }

    /**
     * Invoquée par le système lorsqu'une notification est supprimée (par l'utilisateur ou par l'application).
     * Utilisée ici pour détecter la fin ou l'annulation d'un trajet Google Maps et nettoyer l'interface de navigation.
     *
     * @param sbn L'objet représentant la notification supprimée.
     */
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (MAPS_PACKAGE.equals(sbn.getPackageName())) {
            Intent intent = new Intent(ACTION_MAPS_UPDATE);
            intent.putExtra("is_navigating", false);
            sendBroadcast(intent);
        }
    }
}