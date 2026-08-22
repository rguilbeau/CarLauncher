package com.rguilbeau.carlauncher.provider.apps;

import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/**
 * Modèle de données représentant une application installée sur le système.
 * Implémente {@link Comparable} pour permettre un tri alphabétique automatique.
 *
 * @author rguilbeau
 * @version 1.0
 */
public class AppInfo implements Comparable<AppInfo> {

    public final String name;
    public final String packageName;
    public final Drawable icon;

    public AppInfo(String name, String packageName, Drawable icon) {
        this.name = name;
        this.packageName = packageName;
        this.icon = icon;
    }

    @Override
    public int compareTo(@NonNull AppInfo other) {
        return this.name.compareToIgnoreCase(other.name);
    }
}
