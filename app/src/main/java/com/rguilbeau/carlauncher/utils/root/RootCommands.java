package com.rguilbeau.carlauncher.utils.root;

import android.view.MotionEvent;

import com.rguilbeau.carlauncher.utils.log.CarLog;

import java.util.Locale;

public class RootCommands {
    private static final String TAG = "RootCommands";

    /**
     * Lance Google Maps sur un écran virtuel spécifique en fermant proprement le PiP.
     *
     * @param displayId L'identifiant de l'écran (ex: 2 ou 3)
     */
    public static void launchMapsOnDisplay(int displayId) {
        CarLog.d(TAG, "Demande de lancement de Maps sur le display " + displayId);

        String command = "appops set com.google.android.apps.maps PICTURE_IN_PICTURE ignore; "
                + "am start --display " + displayId + " -n com.google.android.apps.maps/com.google.android.maps.MapsActivity; ";
                //+ "sleep 1; "
                //+ "appops set com.google.android.apps.maps PICTURE_IN_PICTURE allow";

        command = "am start --display " + displayId + " -n com.google.android.apps.maps/com.google.android.maps.MapsActivity; ";
        //+ "sleep 1; "
        //+ "appops set com.google.android.apps.maps PICTURE_IN_PICTURE allow";

        RootTcpClient.getInstance().send(command);
    }

    /**
     * Transmet un événement tactile complet (multi-touch) au Daemon Root via TCP.
     *
     * @param event L'événement MotionEvent capturé
     * @param targetDisplayId L'ID du Virtual Display cible
     */
    public static void sendTouchToRootService(MotionEvent event, int targetDisplayId) {
        int pointerCount = event.getPointerCount();
        StringBuilder pointersData = new StringBuilder();

        for (int i = 0; i < pointerCount; i++) {
            pointersData.append(event.getX(i)).append(",")
                    .append(event.getY(i)).append(",")
                    .append(event.getPointerId(i));
            if (i < pointerCount - 1) {
                pointersData.append(";");
            }
        }

        // Format transmis : TOUCH|displayId|action|downTime|eventTime|pointerCount|x0,y0,id0;x1,y1,id1...
        String payload = String.format(Locale.US, "TOUCH|%d|%d|%d|%d|%d|%s",
                targetDisplayId,
                event.getAction(),
                event.getDownTime(),
                event.getEventTime(),
                pointerCount,
                pointersData.toString()
        );

        RootTcpClient.getInstance().send(payload);
    }

    public static void moveMapsToPipOnMainDisplay() {
        CarLog.d(TAG, "Passage de Maps en PiP sur le Display 0");

        // 1. Réautorise le PiP
        // 2. Bascule Maps sur l'écran principal (Display 0) pour qu'Android génère le PiP flottant
        //String command = "appops set com.google.android.apps.maps PICTURE_IN_PICTURE allow; "
        //        + "am start --display 0 -n com.google.android.apps.maps/com.google.android.maps.MapsActivity";

        //RootTcpClient.getInstance().executeCommand(command);
    }
}