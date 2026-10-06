# AnnoCraft1800

Socle d’un mod Minecraft de gestion d’archipels, inspiré d’Anno 1800. Minecraft **1.20.1**, Forge **47.4.0**, Java **17**. Mod requis sur le client et le serveur ; aucune dépendance Reign of the Nether ou Continents.

## Essayer le socle

Dans PowerShell, depuis ce dossier :

```powershell
./tools/dev.ps1 build
./tools/dev.ps1 client
```

Le script utilise `JAVA_HOME`, ou le JDK local dans `.tools/java` lorsqu’il existe. Sur une autre machine, installer un JDK 17 et définir `JAVA_HOME`. Gradle et les dépendances Forge sont téléchargés par le wrapper. Les structures NBT sont déjà incluses : Python n’est pas nécessaire pour compiler.

Créer un monde puis exécuter `/anno join`. **F6** ouvre la gestion. Choisir une construction, déplacer le curseur sur le terrain, **R** pour la rotation et clic gauche pour construire. Clic droit annule. Flèches ou WASD déplacent la caméra ; Q/E la font pivoter et la molette règle le zoom. Cliquer un bâtiment ouvre ses informations, son amélioration disponible et sa démolition. **F6/Échap** restitue la vue et l’orientation du personnage. Le raccourci F6 est configurable dans les contrôles Minecraft.

Les aperçus affichent l’emprise et les blocs de la véritable structure reçue du serveur. La validation du serveur reste décisive. Budget illimité : la production et les finances ne font pas encore partie de ce jalon.

`/anno leave` ramène dans la dimension, à la position et au mode de jeu précédents. `/anno status` affiche le nombre de bâtiments, la révision de sauvegarde et les tickets de caméra actifs. Les visiteurs jouent en aventure ; les constructions gérées sont protégées des modifications manuelles, explosions, pistons et placements de fluides.

## Coopération

Copier `build/libs/annocraft1800-0.1.0.jar` dans `mods` sur un serveur Forge 47.4.0 et sur chaque client. Tous les joueurs rejoignant l’archipel partagent la même colonie et les mêmes droits. La sélection et la caméra restent personnelles.

Pour un serveur de développement : `./tools/dev.ps1 server`. Au premier lancement, Minecraft demande de lire et accepter son EULA dans `run/eula.txt`. Aucune acceptation n’est intégrée au projet. Le serveur de développement utilise l’authentification normale ; utiliser des clients Minecraft authentifiés pour le test réseau à deux joueurs.

## Monde et données

La dimension `annocraft1800:archipelago` est distincte de l’Overworld. La graine du monde détermine huit îles séparées, leurs contours, plages planes, fertilités et gisements. Les métadonnées sont enregistrées dès le démarrage du serveur. Les plateaux intérieurs restent plats pour la construction ; caves, minerais exploitables et décorations sont prévus dans les jalons suivants.

Les paramètres `region_size` (4096 par défaut) et `island_count` (8) sont définis dans `data/annocraft1800/dimension/archipelago.json` et peuvent être remplacés par un datapack **avant la création du monde**. Modifier ces paramètres sur un monde existant est refusé pour éviter de dissocier bâtiments et géographie.

Les définitions JSON dans `data/annocraft1800/annocraft_buildings` référencent des structures vanilla `.nbt`. Les trois bâtiments de base sont la résidence, l’entrepôt et le comptoir ; la résidence possède une variante améliorée. `python tools/generate_structures.py` régénère les assets originaux, sans bibliothèque tierce.

La colonie est sauvegardée dans `world/data/annocraft1800_colony.dat`, format version 1. Les instances conservent identifiants, position, rotation, île, volume et blocs d’origine pour la démolition. Un format incompatible ou illisible est refusé avant que Minecraft puisse le remplacer par une sauvegarde vide. Sauvegarder le monde entier avant toute modification de données ou de datapacks.

Les commandes réseau sont validées sur le thread serveur. Le terrain non chargé n’est pas généré par une commande de construction. Chaque caméra dispose de 169 tickets maximum (carré de 13 × 13 chunks), de limites de déplacement et d’une expiration après 100 ticks sans signal. Sortie, changement de dimension, déconnexion et arrêt libèrent ses tickets. Les changements de bâtiments sont aussi envoyés aux caméras éloignées ; les chunks du personnage sont renvoyés au retour en visite.

## Vérification

```powershell
./tools/dev.ps1 test
./tools/dev.ps1 gametest
```

JUnit vérifie trois graines, séparation des huit îles, zones constructibles et côtières, indépendance de l’ordre d’exploration, bornes et calculs de caméra. Les GameTests lancent Minecraft côté serveur et vérifient constructions concurrentes, quatre rotations, amélioration, démolition, aller-retour de sauvegarde, refus d’un format futur et 100 bâtiments avec deux sessions de caméra. Ils sont enregistrés uniquement dans la configuration de test, dans `run-gametest`, séparée du monde de développement.

Un test graphique intégré est disponible avec `./gradlew.bat runClient -PclientSmoke` : il crée un monde isolé, teste les clics dans le monde, construction/amélioration et dix bascules de caméra, puis ferme le client. Résultat dans `run-client-smoke/smoke-result.txt` et captures dans son dossier `screenshots`.

Le banc réseau peut lancer deux vrais clients de développement sur un serveur GameTest avec connexion TCP locale. Après `./gradlew.bat exportClientLaunch -PclientSmoke`, lancer `./gradlew.bat runGameTestServer -PnetworkSmoke` dans un terminal et `python tools/network_clients.py` dans un autre. Le serveur écoute uniquement `127.0.0.1:25575`, sans authentification, pour ces clients de test ; cela ne modifie pas la configuration du serveur normal. Les clients et leurs paramètres sont isolés dans `run-client-network-a` et `run-client-network-b`.

Pour vérifier le redémarrage et la reconnexion, après ce premier essai et l’arrêt du serveur, copier `run-network-smoke/world/data/annocraft1800_colony.dat` vers `run-network-smoke/network-expected-colony.dat`. Relancer le serveur avec `-PnetworkSmoke -PnetworkReload` puis le script clients. Le serveur compare l’état complet de la colonie sauvegardée avant d’accepter les deux reconnexions.

Les faux joueurs des GameTests couvrent la logique serveur et la charge de 100 bâtiments ; les clients graphiques couvrent le rendu et le transport réseau réel. La checklist dans `docs/ACCEPTANCE.md` complète ces essais par la session utilisateur prolongée.

## Suite du projet

1. Stocks par île, routes, entrepôts, production, besoins, main-d’œuvre et finances ; boucle paysans–poisson.
2. Population jusqu’aux investisseurs, services, chaînes industrielles et Nouveau Monde.
3. Ports, navires, cargaisons et routes commerciales avec simulation hors chunks chargés.
4. Diplomatie, pirates, combat naval et conquête.
5. Campagne adaptée à Minecraft avec dialogues réécrits et assets originaux.

Les DLC sont hors du périmètre initial. Licence GPL-3.0-only ; voir `LICENSE` et `NOTICE.md` pour le réemploi ciblé de Reign of the Nether et l’audit de dépendances. `build/distributions/annocraft1800-0.1.0-source-distribution.zip` contient les sources, tests, ressources, générateur de structures, wrapper et scripts de compilation.
