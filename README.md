# Lammprimante

Application d'impression par lots de documents PDF, images et ZIP.

## Fonctionnalités

- Impression par lots configurables (15 pages par défaut)
- Support PDF, images (JPG, PNG, BMP, GIF, TIFF) et ZIP
- Glisser-déposer de fichiers et dossiers
- Options : recto/verso, orientation, couleur/N&B, pages par feuille, copies
- Reprise automatique en cas d'échec d'impression (backoff + reprise au lot)
- Thème light/dark switchable à chaud
- Préférences et position de fenêtre sauvegardées

## Stack

- Java 25
- Swing + [LammUI](../LammUI) pour l'identité visuelle
- PDFBox 3.0 pour le rendu et le découpage PDF
- Launch4j pour l'exe Windows

## Prérequis

- Java 25+
- Maven 3.9+
- LammUI installé localement (`cd ../LammUI && mvn install`)

## Build

```bash
mvn package
```

Produit :
- `target/lammprimante-X.Y.jar` (uber jar shadé)
- `target/Lammprimante.exe` (wrapper Windows via Launch4j)

## Lancement

```bash
java -jar target/lammprimante-X.Y.jar
```

## Distribution Windows

Le zip Windows inclut un JRE embarqué minimal (généré via `jlink`, modules `java.base, java.desktop, java.logging, java.prefs`).

**Prérequis** : JDK Windows 25 full (avec jmods) extrait dans `~/tools/jdk25-win/`. Récupérable chez Liberica :

```bash
curl -sL -o /tmp/jdk.zip "https://github.com/bell-sw/Liberica/releases/download/25.0.1+13/bellsoft-jdk25.0.1+13-windows-amd64-full.zip"
mkdir -p ~/tools && cd ~/tools && unzip -q /tmp/jdk.zip && mv jdk-25* jdk25-win
```

Build :

```bash
mvn -Pdist-win clean package
```

Produit `target/Lammprimante-Windows.zip` (~45 Mo) contenant `Lammprimante.exe` + `jre/`. L'utilisatrice dézippe et double-clique sur l'exe.
