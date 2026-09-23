package com.rguilbeau.carlauncher.service.telemetry.canbus.data;

import com.rguilbeau.carlauncher.utils.log.CarLog;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Valeur observable générique avec diffing automatique : les abonnés ne sont notifiés que
 * lorsque la valeur change réellement (comparaison via {@link Objects#equals}).
 * <p>
 * <b>Rejeu à l'abonnement</b> : {@link #bind} notifie immédiatement le nouvel abonné avec la
 * valeur courante (comme le patron addListener/removeListener déjà utilisé ailleurs dans l'app),
 * puis à chaque {@link #set} suivant qui change la valeur.
 * </p>
 * <p>
 * <b>Thread d'exécution</b> : le rejeu initial de {@link #bind} s'exécute sur le thread appelant
 * de {@code bind} (typiquement le thread principal, depuis un {@code onServiceConnected}). Les
 * notifications suivantes, déclenchées par {@link #set}, s'exécutent sur le thread appelant de
 * {@code set} — dans le cas de {@link com.rguilbeau.carlauncher.service.telemetry.canbus.CanBus},
 * celui de lecture du bus CAN, jamais le thread principal. Un même observateur peut donc être
 * invoqué depuis deux threads différents selon l'appel : celui qui touche à l'UI ou à un état non
 * thread-safe doit lui-même repasser sur le thread principal (ex: {@code Handler}/
 * {@code runOnUiThread}/{@code View.post}) avant d'agir.
 * </p>
 * <p>
 * <b>Désabonnement</b> : {@link #unbind} compare par égalité de référence. Une référence de
 * méthode (ex: {@code this::onChanged}) crée une nouvelle instance de {@link Consumer} à chaque
 * évaluation : la repasser à {@link #unbind} après l'avoir passée à {@link #bind} ne retirera
 * donc rien (fuite d'abonnement silencieuse). Pour pouvoir se désabonner, conserver la même
 * instance de {@link Consumer} dans un champ et la réutiliser pour les deux appels :
 * <pre>{@code
 * private final Consumer<Integer> rpmObserver = this::onRpmChanged;
 * ...
 * vehicleData.rpm.bind(rpmObserver);
 * ...
 * vehicleData.rpm.unbind(rpmObserver);
 * }</pre>
 * </p>
 *
 * @param <T> Type de la valeur observée.
 */
public class Property<T> {

    /** Tag utilisé pour l'identification des messages de journalisation de cette classe. */
    private static final String TAG = "Property";

    /**
     * Valeur courante, protégée par le moniteur de l'instance (voir {@link #set}/{@link #get}/
     * {@link #bind}).
     */
    private T value;

    /**
     * Abonnés notifiés à chaque changement de {@link #value}. {@link CopyOnWriteArrayList} pour
     * permettre un abonnement/désabonnement concurrent pendant l'itération de notification.
     */
    private final List<Consumer<T>> observers = new CopyOnWriteArrayList<>();

    public Property(T defaultValue) {
        this.value = defaultValue;
    }

    /**
     * Met à jour la valeur et notifie les observateurs si elle a changé (diffing via
     * {@link Objects#equals}). Les observateurs sont appelés de façon synchrone sur le thread
     * appelant (voir doc de classe) ; une exception levée par l'un d'eux est journalisée et
     * n'empêche pas la notification des autres, ni ne remonte au thread appelant.
     *
     * @param newValue Nouvelle valeur.
     */
    public synchronized void set(T newValue) {
        if (!Objects.equals(this.value, newValue)) {
            this.value = newValue;
            for (Consumer<T> observer : observers) {
                notifySafely(observer, newValue);
            }
        }
    }

    /**
     * @return La valeur courante.
     */
    public synchronized T get() {
        return value;
    }

    /**
     * Abonne {@code observer} aux changements de valeur et le notifie immédiatement avec la
     * valeur courante (voir doc de classe). Sans effet si {@code observer} est {@code null} ou
     * déjà abonné.
     *
     * @param observer Callback à invoquer immédiatement, puis à chaque changement.
     */
    public synchronized void bind(Consumer<T> observer) {
        if (observer == null || observers.contains(observer)) return;

        observers.add(observer);
        notifySafely(observer, value);
    }

    /**
     * Désabonne {@code observer} (voir l'avertissement de la doc de classe sur les références de
     * méthode).
     */
    public void unbind(Consumer<T> observer) {
        observers.remove(observer);
    }

    private void notifySafely(Consumer<T> observer, T notifiedValue) {
        try {
            observer.accept(notifiedValue);
        } catch (Exception e) {
            CarLog.e(TAG, "Erreur dans un observateur de Property", e);
        }
    }
}
