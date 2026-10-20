# BodyVision — application mobile

Projet académique M2 CCM (INSSET). Application Android qui analyse la posture pendant
les exercices de musculation : détection de pose **sur le téléphone**, alerte posturale
calculée hors ligne, envoi différé des coordonnées vers une plateforme Cloud.

Ce fichier complète les instructions globales ; il ne les remplace pas. Il ne contient
que ce qui est propre à ce projet.

## Appareil de test

Honor 400 (DNY-NX9), Snapdragon 7 Gen 3, Android 16. **Appareil physique uniquement.**

Jamais d'émulateur pour la caméra, ML Kit ou les mesures de performance : pas de vraie
caméra, pas le bon GPU, pas de gestion thermique. Un test sur émulateur ne prouve rien ici.

## Décisions figées — ne pas rouvrir

Issues de tickets clos et validés en équipe.

- **Configuration ML Kit** : modèle de base, accélération `CPU_GPU`, résolution d'analyse
  640x480, `STREAM_MODE`. Mesuré sur l'appareil cible : 29,4 FPS, latence médiane 26,4 ms,
  stable sur 10 minutes. Le modèle `accurate` et le 1280x720 ont été écartés.
- **Backpressure** : `ImageAnalysis` reste en `STRATEGY_KEEP_ONLY_LATEST`. Toute autre
  valeur fait s'accumuler les frames et fausse la latence.
- **Chauffe ML Kit** : l'accélération matérielle est choisie de façon non bloquante, les
  premières exécutions tournent sur CPU. Toute mesure de performance exclut les 30
  premières secondes.
- **Interface** : Jetpack Compose. `PreviewView` de CameraX s'intègre via `AndroidView`.
- **Architecture** : découpage **par fonctionnalité** (`camera/`, `pose/`, `session/`…),
  avec les couches à l'intérieur de chaque fonctionnalité. Les dépendances pointent
  toujours vers le domaine, qui ne connaît ni Android, ni ML Kit, ni CameraX.

## Kotlin — pièges de ce projet

- **Jamais de `!!`.** Les landmarks ML Kit sont nullables par nature : un `PoseLandmark`
  absent est un cas normal, pas une erreur. Gère le null explicitement.
- **`ImageProxy.close()` est obligatoire**, y compris sur le chemin d'erreur. Un `close()`
  manqué gèle la caméra au bout de quelques frames, sans message.
- L'analyse d'image tourne sur son propre exécuteur, jamais sur le thread principal.
- Les coordonnées ML Kit dépendent de la rotation de l'image : normaliser avant usage.

## Périmètre actuel — sprint S3

Tickets en cours : SCRUM-30 (initialisation, lint, tests), SCRUM-31 (CameraX et
permissions), SCRUM-32 (détection de pose en flux live).

**Pas de code réseau dans ce dépôt pour l'instant.** Le client HTTP, le cache hors-ligne
et la file d'envoi arrivent au sprint S6. La règle générale « prévoir le hors-ligne dès
le départ » s'applique à la conception, pas à l'implémentation : ne crée pas de couche
réseau pour un backend qui n'existe pas encore.

Pas de base de données locale non plus tant qu'aucun écran n'en a besoin.

## Ce qui existe déjà

La branche `spike/mlkit-pose` contient le PoC du ticket SCRUM-22, mesuré et validé sur
appareil réel : CameraX branché, boucle ML Kit, `PoseGeometry` (angles par `atan2`),
`PoseConfig`, overlay squelette, export JSON d'une frame.

**Lis cette branche avant d'écrire du code équivalent.** `PoseGeometry`, `PoseConfig` et
l'export JSON sont du Kotlin pur, réutilisables tels quels.

**Ne pas supprimer cette branche** : elle est la preuve des mesures, référencée depuis la
note Confluence de SCRUM-22.

**Ne pas reprendre le `CLAUDE.md` qui s'y trouve** : il décrit un spike jetable et
interdit l'architecture et les tests. Il ne vaut que pour cette branche.

## Contrat d'interface

Le contrat OpenAPI fait foi et vit dans le dépôt `BodyVision-CCM/bodyvision-contracts`,
branche `main`. Ne le recopie pas ici : une copie locale périme en silence.

Format de télémétrie, pour information — à implémenter en S6, pas avant : analyse à
30 FPS pour l'alerte locale, sous-échantillonnage à 10 FPS avant envoi, encodage compact
`lm: [[x, y, z, v]]`, coordonnées normalisées 0..1, horodatage relatif, idempotence par
`batchId`, transport gzip.

## Conventions d'équipe

- Branche par ticket : `feat/scrum-NN-description-courte`.
- **Messages de commit en français**, format court : `feat:`, `fix:`, `refactor:`,
  `docs:`, `test:`. C'est la convention de ce dépôt et elle l'emporte ici sur la règle
  globale de rédiger en anglais.
- Une pull request vers `main`, merge en squash, une relecture obligatoire.
- `main` est protégée. Pas de commit direct.

## Commandes

```bash
./gradlew build          # compilation
./gradlew test           # tests unitaires
./gradlew lint           # lint Android
./gradlew installDebug   # déploiement sur l'appareil connecté
```
