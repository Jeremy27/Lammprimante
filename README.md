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
- Swing + [LammUI](https://github.com/Jeremy27/LammUI) (récupérée depuis GitHub Packages)
- PDFBox 3.0 pour le rendu et le découpage PDF
- `jpackage` pour générer les installateurs natifs

## Prérequis

- Java 25+
- Maven 3.9+
- Accès à GitHub Packages pour tirer LammUI (cf. section ci-dessous)

## Accès à LammUI depuis GitHub Packages

LammUI est publiée sur `https://maven.pkg.github.com/Jeremy27/LammUI`. Maven a besoin d'un token pour la télécharger. Dans `~/.m2/settings.xml` :

```xml
<servers>
    <server>
        <id>github-lammui</id>
        <username>Jeremy27</username>
        <password>PASTE_YOUR_PAT_HERE</password>
    </server>
</servers>
```

Le PAT doit avoir le scope `read:packages` (à générer sur https://github.com/settings/tokens/new).

## Build

```bash
mvn package
```

Produit `target/lammprimante-X.Y.jar` (uber jar shadé).

## Lancement

```bash
java -jar target/lammprimante-X.Y.jar
```

## Installateurs natifs

Générés via `jpackage`, qui crée un JRE minimal embarqué (modules `java.base, java.desktop, java.logging, java.prefs`).

### Linux (.deb)

Prérequis système : `fakeroot` et `binutils` (`sudo apt install fakeroot binutils`).

```bash
mvn -Pdist-linux clean package
```

Produit `target/lammprimante_X.Y_amd64.deb` (~25 Mo). Installation : `sudo dpkg -i target/lammprimante_*.deb` → Lammprimante installée dans `/opt/lammprimante`, entrée de menu créée.

### Windows (.msi)

Prérequis système : WiX Toolset 3.x dans le PATH.

```bash
mvn -Pdist-win clean package
```

Produit `target/Lammprimante-X.Y.msi`. Installation : double-clic → wizard, raccourci menu Démarrer + bureau, désinstallable via "Ajouter/Supprimer programmes".

## Release automatique

Un tag `vX.Y` poussé sur le repo déclenche le workflow `.github/workflows/release.yml` qui :
1. Vérifie que la version du pom matche le tag
2. Build le `.deb` (runner Linux) et le `.msi` (runner Windows) en parallèle
3. Crée une GitHub Release et y attache les deux installateurs

Workflow recommandé : utiliser [Lammrelease](https://github.com/Jeremy27/Lammrelease) pour bumper + tag + push — le reste est automatisé.
