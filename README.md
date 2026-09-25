# BodyVision — Spike SCRUM-22

Ce dépôt contient **uniquement un spike jetable**, pas l'application BodyVision finale.
Le seul but de ce code est de mesurer si ML Kit Pose Detection tient 30 FPS sur un
Honor 400 (Snapdragon 7 Gen 3, Android 16) sans surchauffer, et si les angles calculés
sont assez stables pour déclencher une alerte posturale fiable.

Aucune architecture en couches, aucun test, aucun envoi réseau : voir `CLAUDE.md` pour
le contexte complet du ticket et les seuils de décision. Le livrable du ticket est une
**note de mesures**, pas cette application.

⚠️ Cette branche (`spike/mlkit-pose`) est une référence et ne sera pas mergée dans
`main`, qui reste vierge pour l'application réelle.

## Ce que l'app mesure

Pour chacune des 4 configurations ML Kit (modèle base/accurate, accélération CPU vs
CPU_GPU, résolution d'analyse 640x480 ou 1280x720) :

- FPS d'analyse (instantané et moyen)
- latence d'inférence (médiane et p95)
- frames caméra droppées
- température batterie et état thermique (avec historique des changements)
- niveau de batterie en début/fin de run
- précision de détection (inFrameLikelihood moyen, côté droit)
- stabilité de l'angle du genou droit (écart-type glissant)

Les 30 premières secondes de chaque run sont une phase d'échauffement ML Kit : les
valeurs sont mesurées et affichées dès le début, mais le résumé de fin de run et le
verdict SEUILS OK / NON ATTEINTS excluent cette phase (colonne `phase` du CSV :
`chauffe` / `mesure`).

Un garde-fou refuse de démarrer un run si la batterie dépasse 35 °C ou si l'état
thermique dépasse LIGHT (bouton "Démarrer quand même" pour passer outre).

## Lancer un run

1. Ouvrir le projet dans Android Studio, lancer sur le Honor 400 (jamais l'émulateur :
   pas de vraie caméra, pas le bon GPU, pas de gestion thermique réaliste).
2. Suivre le protocole rappelé au lancement de l'app (trépied, luminosité à 50 %,
   téléphone débranché, 15 min de refroidissement entre deux runs, 3 runs par config).
3. Choisir une configuration (spinner) et une durée : **Comparaison** (4 min, pour
   comparer rapidement les configs) ou **Endurance** (10 min, run officiel pour la
   note de mesures).
4. Appuyer sur **Démarrer**. Le run s'arrête automatiquement à la durée choisie ; un
   arrêt manuel avant terme reste possible mais est signalé comme non valide dans le
   résumé.
5. À l'arrêt, un résumé (FPS, latences, thermique, verdict) s'affiche à recopier dans
   la note de mesures, et le CSV est écrit sur le téléphone.

Bouton **Frame JSON** (bonus SCRUM-23) : sérialise la dernière frame de pose détectée
et affiche une estimation du poids d'une télémétrie de 30 s à 30 FPS (brute et gzip).

## Où sont écrits les fichiers

Dans le dossier public **Documents** du téléphone (via `MediaStore`, visible depuis un
explorateur de fichiers ou en USB, sans commande adb) :

```
Documents/bodyvision_spike_<config>_<horodatage>.csv
Documents/bodyvision_spike_<config>_<horodatage>.json   (bouton Frame JSON)
```

Le CSV contient une ligne d'en-tête (modèle, config, résolution, durée choisie/réelle,
run écourté ou non, départ à chaud ou non, batterie début/fin), puis une ligne par
seconde écoulée du run.
