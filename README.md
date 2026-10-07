# AnnoCraft1800

Mod Minecraft de gestion d’archipels, inspiré d’Anno 1800. Minecraft **1.20.1**, Forge **47.4.0**, Java **17**. Mod requis sur le client et le serveur ; aucune dépendance Reign of the Nether ou Continents.

## Essayer

Dans PowerShell, depuis ce dossier :

```powershell
./tools/dev.ps1 build
./tools/dev.ps1 client
```

Le script utilise `JAVA_HOME`, ou le JDK local dans `.tools/java` lorsqu’il existe. Sur une autre machine, installer un JDK 17 et définir `JAVA_HOME`. Gradle et les dépendances Forge sont téléchargés par le wrapper. Les structures NBT sont déjà incluses. Aucun outil autre que le JDK n’est nécessaire.

Créer un monde puis exécuter `/anno join`. **F6** ouvre la gestion. Choisir une construction, déplacer le curseur sur le terrain, **R** pour la rotation et clic gauche pour construire. Clic droit annule. WASD ou les flèches déplacent la caméra ; Q/E la font pivoter et la molette règle le zoom. Cliquer un bâtiment ouvre ses informations, son amélioration disponible et sa démolition. Survoler un bouton du catalogue affiche son coût, son entretien, sa main-d’œuvre et sa production. **F6/Échap** restitue la vue et l’orientation du personnage. Le raccourci F6 est configurable dans les contrôles Minecraft.

Les aperçus affichent l’emprise et les blocs de la véritable structure reçue du serveur. La validation du serveur reste décisive.

`/anno leave` ramène dans la dimension, à la position et au mode de jeu précédents. `/anno status` affiche bâtiments, routes, révision de sauvegarde, tickets de caméra actifs, trésor et solde par minute. Les visiteurs jouent en aventure ; les constructions gérées et les routes sont protégées des modifications manuelles, explosions, pistons et placements de fluides.

## Économie : première boucle paysans–poisson

La colonie démarre avec 5 000 pièces d’or. Fonder une île :

1. Construire un **comptoir côtier** (500 or). Le premier entrepôt de la colonie reçoit la cargaison fondatrice : 30 planches et 10 poissons.
2. Tracer des **routes** (bouton *Route* : clic au départ, puis à chaque angle ; *Retirer* les supprime). Elles sont gratuites. Un bâtiment est relié lorsqu’une route touche son emprise et rejoint un comptoir ou un entrepôt de la même île.
3. Construire des **résidences de paysans** (2 planches). Chaque maison reliée accueille jusqu’à 10 paysans, qui paient des impôts et consomment du poisson.
4. Une **pêcherie** côtière (15 paysans) produit du poisson. La **cabane de bûcheron** (5 paysans) produit du bois ; la **scierie** (10 paysans) le transforme en planches.
5. Une résidence pleine, reliée et approvisionnée peut devenir une **résidence d’ouvriers** (100 or, 4 planches ; 20 habitants, impôts doublés). Les paysans qui partent réduisent la main-d’œuvre disponible.

Règles de simulation (côté serveur, une étape par seconde, indépendante des chunks chargés) :

- **Stocks par île.** Chaque comptoir ou entrepôt ajoute 60 places par marchandise. Une production s’arrête lorsque sa marchandise est pleine.
- **Main-d’œuvre.** Les habitants des maisons reliées fournissent la main-d’œuvre de leur île et de leur strate. En cas de pénurie, toutes les productions de l’île ralentissent proportionnellement.
- **Besoins.** 0,05 poisson par habitant et par minute. Une maison approvisionnée à 95 % se remplit ; sans poisson, sa population tombe à la moitié et ses impôts diminuent. Une maison non reliée garde un seul habitant.
- **Finances.** Impôts (1 or par paysan et 2 par ouvrier, par minute) moins l’entretien des bâtiments. Les coûts sont prélevés à la construction : l’or sur le trésor commun, les matériaux dans les stocks de l’île. La démolition ne rembourse rien.

Le panneau en haut à droite montre l’île sous la caméra (ou celle du bâtiment sélectionné) : stock, flux par minute et main-d’œuvre. Le panneau du bâtiment sélectionné indique son statut : en activité, hors réseau routier, main-d’œuvre, intrants, stockage plein ou besoins.

Les valeurs (coûts, cycles, besoins, impôts, capacités) sont dans l’objet `economy` des JSON de `data/annocraft1800/annocraft_buildings` et peuvent être remplacées par datapack.

**Bac à sable.** `/anno sandbox true` (opérateur) rend la construction gratuite ; `/anno sandbox false` rétablit les coûts. Les colonies créées avant la version 0.2.0 sont migrées en bac à sable pour rester jouables telles quelles.

## Coopération

Copier `build/libs/annocraft1800-0.2.0.jar` dans `mods` sur un serveur Forge 47.4.0 et sur chaque client. Tous les joueurs rejoignant l’archipel partagent la même colonie, le même trésor, les mêmes stocks et les mêmes droits. La sélection et la caméra restent personnelles. Le protocole réseau 2 refuse les clients en version 0.1.0.

Pour un serveur de développement : `./tools/dev.ps1 server`. Au premier lancement, Minecraft demande de lire et accepter son EULA dans `run/eula.txt`. Aucune acceptation n’est intégrée au projet. Le serveur de développement utilise l’authentification normale ; utiliser des clients Minecraft authentifiés pour le test réseau à deux joueurs.

## Monde et données

La dimension `annocraft1800:archipelago` est distincte de l’Overworld. La graine du monde détermine huit îles séparées, leurs contours, plages planes, fertilités et gisements. Les métadonnées sont enregistrées dès le démarrage du serveur. Les plateaux intérieurs restent plats pour la construction ; caves, minerais exploitables et décorations sont prévus dans les jalons suivants.

Les paramètres `region_size` (4096 par défaut) et `island_count` (8) sont définis dans `data/annocraft1800/dimension/archipelago.json` et peuvent être remplacés par un datapack **avant la création du monde**. Modifier ces paramètres sur un monde existant est refusé pour éviter de dissocier bâtiments et géographie.

Les définitions JSON dans `data/annocraft1800/annocraft_buildings` référencent des structures vanilla `.nbt` : comptoir, entrepôt, résidence (et sa variante d’ouvriers), pêcherie, cabane de bûcheron et scierie. `java tools/GenerateStructures.java`, exécuté depuis ce dossier avec le JDK 17, régénère ces assets originaux.

La colonie est sauvegardée dans `world/data/annocraft1800_colony.dat`, format version 2 : bâtiments, routes (avec le bloc d’origine pour la restauration), trésor, stocks par île et état de chaque bâtiment (habitants, cycle, approvisionnement). Les sauvegardes en version 1 sont migrées au chargement. Les instances conservent identifiants, position, rotation, île, volume et blocs d’origine pour la démolition. Un format inconnu ou illisible est refusé avant que Minecraft puisse le remplacer par une sauvegarde vide. Sauvegarder le monde entier avant toute modification de données ou de datapacks.

Les commandes réseau sont validées sur le thread serveur. Le terrain non chargé n’est pas généré par une commande de construction. Une route mesure au plus 95 blocs par tronçon et ne s’applique qu’entièrement. Chaque caméra dispose de 169 tickets maximum (carré de 13 × 13 chunks), de limites de déplacement et d’une expiration après 100 ticks sans signal. Sortie, changement de dimension, déconnexion et arrêt libèrent ses tickets. Les changements de bâtiments et de routes sont aussi envoyés aux caméras éloignées ; les chunks du personnage sont renvoyés au retour en visite. L’état économique est diffusé toutes les deux secondes dans un paquet distinct, sans les aperçus de structures.

## Vérification

```powershell
./tools/dev.ps1 test
./tools/dev.ps1 gametest
```

JUnit vérifie trois graines, séparation des huit îles, zones constructibles et côtières, indépendance de l’ordre d’exploration, bornes et calculs de caméra. Il simule aussi la boucle économique complète pendant 15 minutes (croissance, poisson, chaîne bois–planches, solde positif), les arrêts de production (route, main-d’œuvre, intrants, stockage), les besoins non satisfaits, les coûts, la séparation des îles et l’aller-retour de sauvegarde.

Les GameTests lancent Minecraft côté serveur et vérifient constructions concurrentes, quatre rotations, amélioration, démolition, aller-retour de sauvegarde, refus d’un format futur, 100 bâtiments avec deux sessions de caméra, ainsi que coûts en or et en matériaux, refus faute de stock, condition d’amélioration, routes (blocs, protection, chevauchement, longueur, retrait et restauration) et migration de la version 1. Ils sont enregistrés uniquement dans la configuration de test, dans `run-gametest`, séparée du monde de développement.

Un test graphique intégré est disponible avec `./gradlew.bat runClient -PclientSmoke` : il crée un monde isolé en bac à sable, teste les clics dans le monde, construction, amélioration, route et réception de l’état économique, dix bascules de caméra, puis ferme le client. Résultat dans `run-client-smoke/smoke-result.txt` et captures dans son dossier `screenshots`.

Le banc réseau lance deux vrais clients de développement sur un serveur GameTest avec connexion TCP locale. Après `./gradlew.bat exportClientLaunch -PclientSmoke`, lancer `./gradlew.bat runGameTestServer -PnetworkSmoke` dans un terminal et `./tools/network_clients.ps1` dans un autre. Le serveur écoute uniquement `127.0.0.1:25575`, sans authentification, pour ces clients de test ; cela ne modifie pas la configuration du serveur normal. Les clients et leurs paramètres sont isolés dans `run-client-network-a` et `run-client-network-b`. L’économie de ce banc reste figée pour permettre la comparaison exacte de la sauvegarde.

Pour vérifier le redémarrage et la reconnexion, après ce premier essai et l’arrêt du serveur, copier `run-network-smoke/world/data/annocraft1800_colony.dat` vers `run-network-smoke/network-expected-colony.dat`. Relancer le serveur avec `-PnetworkSmoke -PnetworkReload` puis le script clients. Le serveur compare l’état complet de la colonie sauvegardée avant d’accepter les deux reconnexions.

Les faux joueurs des GameTests couvrent la logique serveur et la charge de 100 bâtiments ; les clients graphiques couvrent le rendu et le transport réseau réel. La checklist dans `docs/ACCEPTANCE.md` complète ces essais par la session utilisateur prolongée.

## Suite du projet

1. ~~Stocks par île, routes, entrepôts, production, besoins, main-d’œuvre et finances ; boucle paysans–poisson.~~ Livré en 0.2.0. Restent à approfondir : transport visible par charrettes et portée des entrepôts, services (marché, taverne), besoins secondaires des paysans, écran de statistiques.
2. Population jusqu’aux investisseurs, services, chaînes industrielles et Nouveau Monde.
3. Ports, navires, cargaisons et routes commerciales avec simulation hors chunks chargés.
4. Diplomatie, pirates, combat naval et conquête.
5. Campagne adaptée à Minecraft avec dialogues réécrits et assets originaux.

Les DLC sont hors du périmètre initial. Licence GPL-3.0-only ; voir `LICENSE` et `NOTICE.md` pour le réemploi ciblé de Reign of the Nether et l’audit de dépendances. `build/distributions/annocraft1800-0.2.0-source-distribution.zip` contient les sources, tests, ressources, générateur de structures, wrapper et scripts de compilation.
