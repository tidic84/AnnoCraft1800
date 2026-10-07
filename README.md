# AnnoCraft1800

Mod Minecraft de gestion d’archipels inspiré d’Anno 1800 : îles générées, chaînes de production, cinq strates de population plus deux dans le Nouveau Monde, flotte et routes commerciales, diplomatie, piraterie, conquête et campagne scénarisée, jouables en coopération avec une vue de gestion (RTS) et une vue de visite à la première personne. Minecraft **1.20.1**, Forge **47.4.0**, Java **17**. Mod requis sur le client et le serveur ; aucune dépendance.

## Essayer

Dans PowerShell, depuis ce dossier :

```powershell
./tools/dev.ps1 build
./tools/dev.ps1 client
```

Le script utilise `JAVA_HOME`, ou le JDK local dans `.tools/java`. Gradle et Forge sont téléchargés par le wrapper. Aucun outil autre que le JDK n’est nécessaire.

Créer un monde puis `/anno join`. Commandes principales :

| Touche / commande | Effet |
| --- | --- |
| **F6** | Vue de gestion ↔ visite à la première personne (position et orientation restaurées) |
| **J** | Gestion de la colonie : aperçu, flotte, commerce, diplomatie, campagne |
| WASD/flèches, Q/E, molette | Déplacer, pivoter, zoomer la caméra |
| Clic, **R**, clic droit | Construire, pivoter, annuler (l’outil reste actif pour enchaîner) |
| **C** | Copier le bâtiment sélectionné |
| Molette sur le catalogue, ‹ › | Défiler, changer de catégorie |
| `/anno join new_world` | Rejoindre le Nouveau Monde (débloqué) |
| `/anno leave`, `/anno status` | Quitter l’archipel ; état de la colonie |
| `/anno campaign start` | Lancer la campagne (aussi depuis l’onglet Campagne) |
| `/anno sandbox true\|false` | Construction gratuite (opérateurs) |

Les visiteurs jouent en aventure ; bâtiments gérés et routes sont protégés (casse, explosions, pistons, fluides, piétinement). Des habitants se promènent autour des maisons habitées près des joueurs et des caméras.

## Économie

- **Fonder une île** : un comptoir côtier (500 or) revendique une île libre. Le premier entrepôt de chaque monde reçoit une cargaison fondatrice (30 planches, 10 poissons). Sans bac à sable, aucun autre bâtiment ne se pose sur une île non revendiquée.
- **Routes** : gratuites, tracées par segments. Un bâtiment produit et une maison se peuple seulement s’ils rejoignent par la route un comptoir ou entrepôt de leur île. En vue de gestion, les bâtiments isolés sont entourés de rouge.
- **Stocks par île** : 60 places par marchandise et par entrepôt. Une production s’arrête quand son stock est plein ou qu’un intrant manque.
- **Main-d’œuvre** : chaque strate travaille dans les bâtiments de sa strate ; en cas de pénurie, toutes les productions concernées de l’île ralentissent.
- **Besoins** : marchandises de base et services (marché, école, université…) ; une maison couverte à 95 % se remplit et peut s’améliorer quand elle est pleine. Le luxe (biens et services) augmente les impôts jusqu’à +50 %. Une alerte prévient quand une île manque d’un bien demandé.
- **Fertilités et gisements** : chaque île a deux sols (céréales, pommes de terre, houblon, vigne ; bananiers, canne, café, tabac dans le Nouveau Monde) et un ou deux gisements (argile, fer, quartz, charbon ; or). Chaque ressource existe quelle que soit la graine. Le panneau d’île les affiche.
- **Déblocages** : les bâtiments d’une strate apparaissent dès son premier habitant.
- **Bonus** : syndicat (+25 %) et centrale électrique au charbon (+100 %) accélèrent les producteurs dans leur rayon.
- **Finances** : impôts moins entretien des bâtiments et des navires. Coûts prélevés à la construction ; la démolition rend la moitié des matériaux.

Strates : paysans → ouvriers → artisans → ingénieurs → investisseurs (Ancien Monde) ; journaliers → contremaîtres (Nouveau Monde). 68 bâtiments, 45 marchandises : voir `tools/content.txt` ou les infobulles du catalogue.

## Nouveau Monde, flotte et commerce

Le Nouveau Monde (`annocraft1800:new_world`) s’ouvre avec le premier artisan ou par la campagne. Le rhum, le café, les cigares et l’or y sont produits et doivent être acheminés vers l’Ancien Monde.

Un **chantier naval** construit goélettes, clippers, frégates, cargos à vapeur et cuirassés (onglet Flotte). Ordres : route commerciale entre deux îles avec un bien à l’aller et un au retour, déplacement, siège, escorte d’un autre navire, démantèlement. Les voyages sont simulés hors des chunks chargés, entre les deux mondes compris ; les navires apparaissent en mer sous forme de modèles en blocs. Un chantier répare les navires à quai.

L’onglet **Commerce** achète et vend auprès des factions qui ne sont pas en guerre ; un accord commercial améliore les prix.

## Diplomatie et conflits

Trois factions : Lady Ashby (négociante), le Commodore Dravek (militaire) et les Corsaires de la Brume (pirates). Chacune possède des îles, visibles comme de petites villes. Relations de −100 à 100 ; actions : cadeau, guerre, trêve (ou tribut pour les pirates), paix, accord commercial, alliance. Les rivaux colonisent des îles libres, les factions en guerre pillent les ports mal défendus (batteries côtières et navires de guerre à quai les repoussent) et les pirates attaquent les navires marchands non escortés. Des navires de guerre assiégeant une île ennemie finissent par la conquérir ; une faction sans île est vaincue.

## Campagne

*L’Héritage Valmont* : neuf missions en cinq chapitres, de l’arrivée dans l’archipel au procès de l’oncle usurpateur, en passant par les pirates, le Nouveau Monde, Lady Ashby et la guerre contre Dravek. Dialogues et personnages sont originaux. Les missions sont des données (`data/annocraft1800/annocraft_campaign/*.json`) et leurs textes sont dans les fichiers de langue.

## Monde et données

Les dimensions `annocraft1800:archipelago` et `annocraft1800:new_world` comptent huit îles chacune, déterminées par la graine. `region_size` et `island_count` se règlent par datapack avant la création du monde.

Le contenu est généré : `java tools/GenerateContent.java`, exécuté depuis ce dossier avec le JDK 17, produit à partir de `tools/content.txt` les définitions JSON, les structures `.nbt` originales et les traductions des bâtiments et marchandises. Toutes les valeurs (coûts, cycles, besoins, rayons…) restent remplaçables par datapack.

La colonie est sauvegardée dans `world/data/annocraft1800_colony.dat`, format 3 : bâtiments, routes, économie, flotte, diplomatie, campagne et villes rivales. Les formats 1 et 2 sont migrés ; un format inconnu ou illisible est refusé avant écrasement. Sauvegarder le monde avant toute modification de données.

Le serveur fait autorité : commandes validées sur son thread, terrain non chargé jamais généré par une commande, tickets de caméra bornés et libérés. Le protocole réseau 3 refuse les clients plus anciens. Définitions et aperçus ne sont envoyés qu’à l’arrivée et au rechargement des datapacks ; l’état économique est diffusé toutes les deux secondes.

## Vérification

```powershell
./tools/dev.ps1 test
./tools/dev.ps1 gametest
./gradlew.bat runClient -PclientSmoke
```

- JUnit (21 tests) : géométrie des îles, boucle économique, services, électricité, navires et routes, diplomatie et sièges, raids, campagne, sauvegardes et cohérence de tout le contenu généré.
- GameTests (6) : constructions concurrentes, rotations, amélioration, démolition, sauvegarde et migration, coûts, routes, protection, taille des paquets, 100 bâtiments avec deux caméras.
- Test graphique : construction, amélioration, route, synchronisation, dix bascules de caméra, captures du jeu et de chaque onglet de la colonie dans `run-client-smoke/screenshots`.
- Banc réseau à deux clients : `./gradlew.bat exportClientLaunch -PclientSmoke`, puis `./gradlew.bat runGameTestServer -PnetworkSmoke` et `./tools/network_clients.ps1` ; variante redémarrage avec `-PnetworkReload` (voir `docs/VALIDATION.md`).

## Suite possible

Les cinq étapes initiales sont livrées (voir `docs/VALIDATION.md`). Pistes : expéditions, spécialistes et objets, attractivité, charrettes visibles sur les routes, IA rivale qui construit réellement, équilibrage sur des parties longues.

Licence GPL-3.0-only ; voir `LICENSE` et `NOTICE.md`. `build/distributions/annocraft1800-0.3.0-source-distribution.zip` contient les sources, tests, ressources, générateur, wrapper et scripts.
