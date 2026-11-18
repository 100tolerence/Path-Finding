# Navigation incrémentale AmberPath

- Le moteur A* ne bloque plus le démarrage jusqu'à la résolution complète de l'objectif.
- Après 60 ms, s'il a trouvé un progrès utile (heuristique améliorée d'au moins 3 blocs), il publie un premier segment partiel.
- Le joueur commence à suivre ce segment immédiatement.
- Pendant ce déplacement, A* continue avec un budget de 8 ms par tick pour calculer le segment suivant depuis la frontière du segment actif.
- Le segment suivant est mis en attente et activé dès que le segment courant est terminé.
- Si le calcul anticipé n'est pas prêt à temps, le navigateur attend sa fin plutôt que de repartir sur un calcul concurrent incohérent.
- Un blocage ou une chute invalide le calcul anticipé et déclenche une nouvelle recherche depuis la position courante.
- Si l'objectif est atteint, l'exécuteur, le calcul en cours et le segment en attente sont libérés.

La recherche reste bornée par le plafond de nœuds déjà présent dans le projet. Le modèle du monde utilisé par un segment anticipé est un instantané construit à son lancement : les collisions et événements du monde sont toujours surveillés par l'exécuteur, qui déclenche un recalcul si le chemin devient invalide.

## v1.2 — Commande /go, arrêt manuel, sauts calibrés, caméra réaliste

- **Plus aucun menu ni touche** : tout passe par `/go <x> <z>`, `/go <x> <y> <z>`, `/go stop` (`/go` seul = aide).
- **Reprise de main = arrêt** : inventaire, chat, pause, touches de déplacement ou caméra à la souris
  stoppent la navigation. Au sol : immédiat. En l'air : le mouvement courant se termine sur son bloc
  (atterrissage) puis arrêt — garde-fou 3 s si le monde est en pause. L'arrivée au bloc destination
  arrête aussi la navigation.
- **Sauts calibrés sur l'élan réel** : la portée proposée dépend de la vitesse de décollage
  (départ arrêté ≈ 62 % de la vitesse sprint, plein régime après 3 blocs de sprint) + marge de
  sécurité 0,45 bloc. Fini les sauts « trop grands » après un virage ou un départ arrêté.
- **Double jump conditionnel** : 2e impulsion au sommet de l'arc, proposée UNIQUEMENT si
  Jump Boost II+, ou Jump Boost I + vitesse ≥ 1.5×, ou ✦ Speed Skyblock ≥ 250. Sinon, jamais.
- **Réaction rapide aux échecs** : collision franche → recalcul en 0,4 s ; absence de progrès → 1 s ;
  saut manqué détecté EN VOL (sous le niveau d'atterrissage, hors de portée) → recalcul immédiat
  + cible blacklistée 20 s. Décollage impossible → abandon après 1,5 s max.
- **Analyse d'environnement avant engagement** : re-validation live de l'atterrissage et du couloir
  de saut avant chaque décollage ; validation des premiers pas de chaque segment ; les poches sans
  issue sont évitées (pénalité A*), les culs-de-sac ne sont plus des cibles involontaires.
- **Caméra réaliste** : double lissage (consigne filtrée + exponentiel plafonné), micro-mouvements
  permanents (±0,7° yaw / ±0,45° pitch), légère inclinaison vers le bas en marche (~3°) avec
  balancement des pas, coups d'œil occasionnels toutes les 4–9 s. Mode « précis » (parkour, portes) :
  bruit réduit, aucun coup d'œil.
