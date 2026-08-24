package com.rguilbeau.carlauncher.provider.apps;

import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/**
 * Modèle de données représentant une application installée sur le système.
 * Implémente {@link Comparable} pour permettre un tri alphabétique automatique.
 *
 * @author rguilbeau
 */
public class AppInfo implements Comparable<AppInfo> {

    public final String name;
    public final String packageName;
    public final Drawable icon;

    /**
     * Construit une nouvelle instance représentant une application.
     *
     * @param name        Le nom d'affichage de l'application (ex: "Google Maps").
     * @param packageName Le nom de paquet unique de l'application (ex: "com.google.android.apps.maps").
     * @param icon        L'icône de l'application à afficher dans la grille.
     */
    public AppInfo(String name, String packageName, Drawable icon) {
        this.name = name;
        this.packageName = packageName;
        this.icon = icon;
    }

    /**
     * Compare cette application avec une autre pour définir leur ordre de tri.
     * Le tri s'effectue par ordre alphabétique sur le nom de l'application, en ignorant la casse (majuscule/minuscule).
     *
     * @param other L'autre application avec laquelle comparer.
     * @return Un entier négatif, zéro ou positif si le nom de cette application se situe respectivement
     * avant, au même niveau, ou après l'autre application dans l'alphabet.
     */
    @Override
    public int compareTo(@NonNull AppInfo other) {
        return this.name.compareToIgnoreCase(other.name);
    }
}