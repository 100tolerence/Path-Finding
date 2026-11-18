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
