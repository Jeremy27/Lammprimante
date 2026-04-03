# Lammprimante

Application d'impression par lots de documents PDF, images et ZIP.

## Fonctionnalités

- Impression par lots (15 pages par défaut, configurable)
- Support PDF, images (JPG, PNG, BMP, GIF, TIFF) et ZIP
- Glisser-déposer de fichiers
- Options d'impression : recto/verso, orientation, couleur/N&B, pages par feuille, copies
- 3 thèmes Material Design (Sombre, Clair, Sombre contrasté)
- Préférences sauvegardées automatiquement

## Prérequis

- Java 17+
- Maven 3.9+

## Build

```bash
mvn package
```

## Lancement

```bash
java -jar target/lammprimante-1.0.jar
```

## Distribution Windows

Le zip Windows inclut un JRE embarqué. L'utilisateur n'a qu'à dézipper et double-cliquer sur `Lammprimante.bat`.
