package com.rguilbeau.carlauncher.repository.client;

public interface IClient {

    /**
     * Exécute une requête de façon synchrone sur le thread appelant.
     *
     * @param query La requête SQL paramétrée à exécuter.
     * @param args  Les valeurs à substituer aux paramètres de la requête, dans l'ordre.
     * @return true si la requête s'est exécutée avec succès, false sinon.
     */
    boolean exec(String query, Object... args);
}
