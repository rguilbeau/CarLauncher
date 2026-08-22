package com.rguilbeau.carlauncher.provider.apps;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Classe utilitaire fournissant un accès statique à la liste des applications du système.
 *
 * @author rguilbeau
 * @version 1.0
 */
public class AppProvider {

    private static final String TAG = "AppProvider";

    /**
     * Récupère la liste de toutes les applications lançables installées sur l'appareil.
     * Exclut le Car Launcher lui-même de la liste.
     *
     * @param context Le contexte Android.
     * @return Une liste triée par ordre alphabétique d'objets {@link AppInfo}.
     */
    public static List<AppInfo> getApps(Context context) {
        List<AppInfo> appList = new ArrayList<>();
        try {
            PackageManager pm = context.getPackageManager();
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

            List<ResolveInfo> resolvedInfos = pm.queryIntentActivities(mainIntent, 0);
            String myPackageName = context.getPackageName();

            for (ResolveInfo info : resolvedInfos) {
                String packageName = info.activityInfo.packageName;

                // On évite d'afficher notre propre launcher dans le tiroir d'applications
                if (myPackageName.equals(packageName)) {
                    continue;
                }

                String appName = info.loadLabel(pm).toString();
                Drawable appIcon = info.loadIcon(pm);

                appList.add(new AppInfo(appName, packageName, appIcon));
            }

            // Tri alphabétique automatique
            Collections.sort(appList);

        } catch (Exception e) {
            Log.e(TAG, "Error while loading installed applications", e);
        }
        return appList;
    }
}
