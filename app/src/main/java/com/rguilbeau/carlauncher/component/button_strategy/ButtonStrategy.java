package com.rguilbeau.carlauncher.component.button_strategy;

import android.content.Context;

/**
 * Interface définissant le contrat des stratégies applicables aux boutons du Car Launcher.
 * <p>
 * L'implémentation du patron de conception Stratégie (Strategy Pattern) permet de modifier
 * dynamiquement le comportement des événements (clic simple et appui long) en fonction du type de bouton.
 * </p>
 *
 * @author rguilbeau
 * @version 1.0
 */
public interface ButtonStrategy {

    /**
     * Exécute l'action associée au clic simple sur le bouton.
     *
     * @param context Le contexte Android nécessaire à l'exécution de l'action (ex: lancement d'Intent).
     */
    void onClick(Context context);

    /**
     * Exécute l'action associée à l'appui long sur le bouton.
     *
     * @param context Le contexte Android nécessaire à l'exécution de l'action.
     * @return true si l'événement a été consommé, false sinon.
     */
    boolean onLongClick(Context context);
}