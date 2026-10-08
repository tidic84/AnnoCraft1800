# AnnoCraft1800

Mod Minecraft de gestion d’archipels inspiré d’Anno 1800 : îles générées, chaînes de production, cinq strates de population plus deux dans le Nouveau Monde, flotte et routes commerciales, diplomatie, piraterie, conquête et campagne scénarisée, jouables en coopération avec une vue de gestion (RTS) et une vue de visite à la première personne. Minecraft **1.20.1**, Forge **47.4.0**, Java **17**. Mod requis sur le client et le serveur ; aucune dépendance.

## Essayer

Dans PowerShell, depuis ce dossier :

```powershell
./tools/dev.ps1 build
./tools/dev.ps1 client
./tools/dev.ps1 shaders   # client avec Oculus et Embeddium
```

Le script utilise `JAVA_HOME`, ou le JDK local dans `.tools/java`. Gradle et Forge sont téléchargés par le wrapper. Aucun outil autre que le JDK n’est nécessaire.

Shaders en développement : `./tools/dev.ps1 shaders` (ou `./gradlew.bat runClient -Pshaders`) télécharge Oculus 1.8.0 et Embeddium 0.3.31 depuis Modrinth et les adapte à l’environnement de développement. Ne pas copier de jars de mods dans `run/mods` : ils sont compilés pour le jeu publié et y plantent au démarrage. Le jar du mod, lui (`build/libs`), s’installe normalement à côté d’Oculus dans une vraie instance Minecraft Forge.

Créer un monde puis `/anno join`. Commandes principales :

| Touche / commande | Effet |
| --- | --- |
| **F6** | Vue de gestion ↔ visite à la première personne ; en quittant la vue, le joueur est posé sur l’île sous la caméra (jamais dans l’eau ni sur un toit) |
| **M** | Carte stratégique de l’archipel ; clic sur une île pour y placer la caméra |
| **J** | Fenêtre de la colonie par-dessus la vue de gestion : aperçu, flotte, commerce, diplomatie, campagne |
| ZQSD (AZERTY) ou WASD, flèches, bords de l’écran | Déplacer (Maj : plus vite) |
| **A**/**E** (AZERTY ; Q/E en QWERTY), clic milieu glissé à gauche-droite | Pivoter la caméra |
| Clic milieu glissé en haut-bas, **Ctrl + molette**, **Pg. préc.**/**Pg. suiv.** | Incliner la caméra vers l’horizon ou à la verticale |
| Molette | Zoomer, jusqu’à 2 500 blocs d’altitude (tout l’archipel) ; le bouton **?** en haut à droite rappelle les touches |
| **1**…**9**, cartes du menu, **B** | Onglet de strate, choisir un bâtiment ; outil route |
| Clic, **R**, Échap / clic droit | Construire, pivoter, terminer (l’outil reste actif pour enchaîner) |
| Cliquer-glisser | Tracer ou retirer une route |
| Clic sur un navire, cadre glissé (Maj : ajouter) | Sélectionner ses navires, comme des troupes |
| Clic droit avec des navires sélectionnés | Mer : naviguer · navire ennemi : l’attaquer · votre île : accoster · île ennemie : assiéger |
| Clic sur votre portrait (en haut à gauche) | Votre compagnie : nom, couleur, drapeau, portrait |
| **C**, **Suppr** | Copier, démolir le bâtiment sélectionné |
| `/anno join new_world` | Rejoindre le Nouveau Monde (débloqué) |
| `/anno leave`, `/anno status` | Quitter l’archipel ; état de la colonie |
| `/anno campaign start` | Lancer la campagne (aussi depuis l’onglet Campagne) |
| `/anno sandbox true\|false` | Construction gratuite (opérateurs) |

## Interface

La vue de gestion reprend le HUD d’Anno 1800, en tons anthracite et parchemin : en haut à gauche le trésor, le solde et la population ; au centre la barre des matériaux de construction de l’île (planches, briques, poutres, fenêtres) au-dessus de la plaque de son nom, qui ouvre l’entrepôt de l’île ; à gauche le livre des quêtes, la colonie, la flotte, le commerce et la diplomatie, le suivi de campagne et le fil de notifications ; en bas à gauche la minimap sous les fertilités et gisements de l’île ; en bas au centre le menu de construction (outils de route et bâtiments logistiques en haut, une rangée de bâtiments, onglets de strate Paysans, Ouvriers… en bas, touches 1 à 9 ; comme dans Anno, les chaînes de production sont regroupées derrière leur produit final, marqué d’une flèche ▲ : Planches ouvre bûcheron et scierie, Pain ouvre ferme céréalière, moulin et boulangerie) ; en bas à droite le panneau d’information du bâtiment survolé (coût, entretien, main-d’œuvre, chaîne, conditions) ou du bâtiment sélectionné (production, besoins, actions). Chaque bâtiment est représenté, comme dans Anno, par ce qu’il produit ou abrite (un poisson pour la pêcherie, le chapeau de paille des paysans pour leur résidence). Pendant la pose, un quadrillage entoure le curseur et le bâtiment apparaît en fantôme vert ou rouge, visible à travers les arbres (le curseur les traverse aussi, puisqu’ils seront abattus), avec le rayon de ses services ; sa couche inférieure (fondations, cours, champs) remplace le sol, et il s’élève couche par couche en deux secondes. Comme dans Anno, chaque parcelle est en terre battue, qui s’arrête net à sa limite pour montrer le terrain de chaque bâtiment ; seules les routes de terre usent le pré sur leurs bords (herbe piétinée puis herbe usée, en dégradé), sans toucher aux parcelles, et l’herbe repousse d’elle-même quand la route disparaît. Les bâtiments portuaires (comptoir, pêcherie, chantier naval, batterie) ont l’avant sur la plage et l’arrière dans la mer, sur un quai de pierre ou des pilotis ; ils pivotent tout seuls face à l’eau. Le suivi de campagne, les notifications et les dialogues restent affichés en vue de visite.

En vue de gestion, le corps du joueur devient invisible et suit la caméra en altitude ; en revenant à la visite, il est posé sur la terre ferme sous la caméra (après une déconnexion, il retrouve sa place d’origine). Le serveur ne charge en détail que les chunks du champ de vision de la caméra, dans un rayon de 176 blocs, rien derrière elle. Au-delà, une vue lointaine inspirée de Distant Horizons et Voxy prend le relais. Tout ce que le joueur a déjà vu y figure tel quel : le client relève la hauteur et la couleur (moyenne de la texture du bloc, teintée par le biome) de chaque colonne des chunks reçus, villes, routes et forêts comprises, et garde ce relevé sur disque par serveur et par monde (`annocraft1800/lod`). Le reste est recalculé à partir de la graine (relief, forêts, collines de gisements) et des blocs des bâtiments. Colonnes d’un bloc près de l’œil, puis de 2, 4, 8 et 16 blocs, maillées en tâche de fond, avec un ombrage doux des creux et des hauts-fonds turquoise autour des îles. On peut dézoomer jusqu’à 2 500 blocs d’altitude et voir tout l’archipel ; la minimap montre le cône de vue. Aucun mod de distance d’affichage n’est requis. En vue de gestion, le brouillard de distance de rendu est supprimé : les vrais chunks rejoignent directement la vue lointaine. La vue lointaine a son propre shader : lumière du soleil selon l’heure (chaude à l’aube et au couchant, clair de lune la nuit), lumière du ciel, brume à la couleur du ciel. Avec un pack de shaders (Oculus/Iris), dont le brouillard s’arrête à la distance de rendu du joueur, elle est dessinée par-dessus l’image du shader en respectant la profondeur de la scène, avec un étalonnage de couleurs proche de celui des packs : les chunks que le shader montre nettement restent les vrais blocs, le reste vient de la vue lointaine. Pour tester : `./tools/dev.ps1 shaders` (Oculus, Embeddium et le pack de `run/shaderpacks` activé dans `run/config/oculus.properties`).

Les visiteurs jouent en aventure ; bâtiments gérés et routes sont protégés (casse, explosions, pistons, fluides, piétinement). Des habitants se promènent autour des maisons habitées près des joueurs et des caméras.

## Économie

- **Fonder une île** : un comptoir côtier (500 or) revendique une île libre. Le premier entrepôt de chaque monde reçoit une cargaison fondatrice (30 planches, 10 poissons). Sans bac à sable, aucun autre bâtiment ne se pose sur une île non revendiquée.
- **Routes** : gratuites, larges de deux blocs, tracées par segments (la seconde voie se resserre le long des bâtiments). Un bâtiment produit et une maison se peuple seulement s’ils rejoignent par la route un comptoir ou entrepôt de leur île. En vue de gestion, les bâtiments isolés sont entourés de rouge.
- **Stocks par île** : 60 places par marchandise et par entrepôt. Une production s’arrête quand son stock est plein ou qu’un intrant manque.
- **Main-d’œuvre** : chaque strate travaille dans les bâtiments de sa strate ; en cas de pénurie, toutes les productions concernées de l’île ralentissent.
- **Besoins** : marchandises de base et services (marché, école, université…) ; une maison couverte à 95 % se remplit et peut s’améliorer quand elle est pleine. Le luxe (biens et services) augmente les impôts jusqu’à +50 %. Une alerte prévient quand une île manque d’un bien demandé.
- **Nature, fertilités et gisements** : les îles sont couvertes de forêts (chênes, bouleaux, épicéas ; jungle et acacias dans le Nouveau Monde), d’herbes et de fleurs, avec une clairière autour de leur centre. Construire ou tracer une route abat les arbres et dégage les plantes. Un bûcheron produit selon les arbres dans un rayon de 11 blocs (12 pour 100 %). Chaque île a deux sols (céréales, pommes de terre, houblon, vigne ; bananiers, canne, café, tabac dans le Nouveau Monde) et un ou deux gisements (argile, fer, quartz, charbon ; or), visibles sur l’île : trois sites par gisement, collines rocheuses veinées de minerai ou fosses d’argile et de sable, avec un emplacement plat. Une mine doit couvrir le centre d’un site ; pendant sa pose, les sites sont encadrés en doré. Le panneau d’île au-dessus de la minimap les affiche.
- **Déblocages** : les bâtiments d’une strate apparaissent dès son premier habitant.
- **Bonus** : syndicat (+25 %) et centrale électrique au charbon (+100 %) accélèrent les producteurs dans leur rayon.
- **Finances** : impôts moins entretien des bâtiments et des navires. Coûts prélevés à la construction ; la démolition rend la moitié des matériaux.

Strates : paysans → ouvriers → artisans → ingénieurs → investisseurs (Ancien Monde) ; journaliers → contremaîtres (Nouveau Monde). 68 bâtiments, 45 marchandises : voir `tools/content.txt` ou les infobulles du catalogue.

## Nouveau Monde, flotte et commerce

Le Nouveau Monde (`annocraft1800:new_world`) s’ouvre avec le premier artisan ou par la campagne. Le rhum, le café, les cigares et l’or y sont produits et doivent être acheminés vers l’Ancien Monde.

Un **chantier naval** construit goélettes (2 cales de 50), clippers (4 × 50), frégates (guerre), cargos à vapeur (6 × 60) et cuirassés (guerre) : fenêtre Flotte, choisir le type et le chantier puis Construire. La liste montre chaque navire (coque, position, ordre) ; le navire choisi affiche ses cales et ses ordres : Naviguer vers une île, Assiéger une île d’une faction en guerre (navires de guerre), route commerciale entre deux îles avec entrepôt (il charge le bien « Aller » au départ, le décharge à l’arrivée, rapporte le bien « Retour », sans fin), escorte d’un navire marchand contre les pirates, démantèlement. Les navires sont des unités, comme dans Anno : chacun a une position réelle sur la mer, accoste le long du quai de son chantier naval ou de son comptoir, et suit des couloirs maritimes qui contournent les îles. On les sélectionne d’un clic ou d’un cadre, puis un clic droit les envoie sur la mer, accoster, assiéger ou chasser un navire ennemi ; un cercle, leur trajet et leur barre de coque s’affichent, et leur panneau donne leurs cales et leurs ordres. Les navires de guerre tirent sur tout ennemi à portée (fumée, gerbes d’eau, détonations). En guerre, les pirates et les factions envoient leurs propres navires de guerre rôder devant vos ports et attaquer vos navires ; les couler rapporte un butin. Les voyages continuent hors des chunks chargés et entre les deux mondes ; les navires sont dessinés en blocs, voiles aux couleurs de leur compagnie. Un chantier répare les navires à quai.

La fenêtre **Commerce** présente les marchands en haut (portrait et relation), l’île de livraison, la grille des marchandises avec le stock de l’île, et le prix d’achat et de vente du bien choisi ; on n’échange pas avec une faction en guerre, et un accord commercial améliore les prix.

## Compagnies, drapeaux et portraits

À son arrivée dans l’archipel, chaque joueur choisit l’identité de sa compagnie, comme dans Anno : son nom, sa couleur (carte, voiles des navires de guerre, cadres), son drapeau peint sur une grille de 24 × 16 (pinceau, pot de peinture, deux couleurs, dix modèles, tirage aléatoire) et son portrait parmi huit personnages ou sa propre apparence Minecraft. Les portraits sont vivants : le buste respire, tourne la tête et parle pendant les dialogues ; ceux des rivaux (Lady Ashby, Dravek, le capitaine Rook) et des personnages de la campagne aussi. Le portrait du joueur et son drapeau sont en haut à gauche du HUD ; un clic rouvre l’écran de la compagnie.

## Coopération ou compétition

Le mode se choisit à la création du monde (Plus d’options du monde › Règles du jeu › « AnnoCraft : une compagnie par joueur ») :

- **Coopération** (par défaut) : tous les joueurs gèrent la même colonie (trésor, îles, flotte) ; l’onglet Diplomatie › Votre compagnie montre les joueurs qui la partagent.
- **Compétition** : chaque joueur fonde sa compagnie, avec son trésor, ses îles, ses bâtiments, sa flotte, ses relations avec les factions et sa campagne. On ne construit pas sur l’île d’un autre. Diplomatie › Compagnies rivales montre chaque rival en personne (portrait, drapeau, îles, habitants) : cadeau de 1 000 or, déclaration de guerre immédiate, proposition de paix, d’accord commercial puis d’alliance, que l’autre accepte ou refuse. En guerre, les navires de guerre des deux compagnies se combattent en mer.

## Diplomatie et conflits

Trois factions : Lady Ashby (négociante), le Commodore Dravek (militaire) et les Corsaires de la Brume (pirates). Chacune possède des îles, visibles comme de petites villes. Relations de −100 à 100 ; actions : cadeau, guerre, trêve (ou tribut pour les pirates), paix, accord commercial, alliance. Les rivaux colonisent des îles libres, les factions en guerre pillent les ports mal défendus (batteries côtières et navires de guerre à quai les repoussent) et les pirates attaquent les navires marchands non escortés. Des navires de guerre assiégeant une île ennemie finissent par la conquérir ; une faction sans île est vaincue.

## Campagne

*L’Héritage Valmont* : neuf missions en cinq chapitres, de l’arrivée dans l’archipel au procès de l’oncle usurpateur, en passant par les pirates, le Nouveau Monde, Lady Ashby et la guerre contre Dravek. Dialogues et personnages sont originaux. Les missions sont des données (`data/annocraft1800/annocraft_campaign/*.json`) et leurs textes sont dans les fichiers de langue.

## Monde et données

Les dimensions `annocraft1800:archipelago` et `annocraft1800:new_world` comptent huit îles chacune, déterminées par la graine. `region_size` et `island_count` se règlent par datapack avant la création du monde.

Le contenu est généré : `java tools/GenerateContent.java`, exécuté depuis ce dossier avec le JDK 17, produit à partir de `tools/content.txt` les définitions JSON, les bâtiments `.nbt` et les traductions des bâtiments et marchandises. Chaque bâtiment a sa propre architecture, dessinée bloc par bloc d’après son modèle d’Anno 1800 (moulin à vent à ailes, église à clocher, pêcherie sur ponton avec filets, mines à galerie boisée, maisons qui passent de la chaumière de bois à la villa à toit de cuivre) ; sa hauteur en découle. Les icônes de l’interface (marchandises, strates, bâtiments publics, outils) sont peintes par `java tools/GenerateIcons.java` dans `assets/annocraft1800/textures/gui/icons`. Toutes les valeurs (coûts, cycles, besoins, rayons…) restent remplaçables par datapack.

La colonie est sauvegardée dans `world/data/annocraft1800_colony.dat`, format 3 : bâtiments, routes, économie, flotte, diplomatie, campagne et villes rivales. Les formats 1 et 2 sont migrés ; un format inconnu ou illisible est refusé avant écrasement. Sauvegarder le monde avant toute modification de données.

Le serveur fait autorité : commandes validées sur son thread, terrain non chargé jamais généré par une commande, tickets de caméra bornés (441 par joueur au plus) et libérés. Le protocole réseau 6 refuse les clients plus anciens. Définitions et aperçus ne sont envoyés qu’à l’arrivée et au rechargement des datapacks ; l’état économique est diffusé toutes les deux secondes.

## Vérification

```powershell
./tools/dev.ps1 test
./tools/dev.ps1 gametest
./gradlew.bat runClient -PclientSmoke
```

- JUnit (22 tests) : géométrie des îles, gisements et forêts, boucle économique, services, électricité, navires et routes, diplomatie et sièges, raids, campagne, sauvegardes et cohérence de tout le contenu généré.
- GameTests (6) : constructions concurrentes, rotations, amélioration, démolition, sauvegarde et migration, coûts, routes, protection, taille des paquets, 100 bâtiments avec deux caméras.
- Test graphique : construction, amélioration, route, synchronisation, dix bascules de caméra, corps caché pendant la vue de gestion, gisement d’argile avec quadrillage, captures du jeu et de chaque onglet de la colonie dans `run-client-smoke/screenshots`.
- Banc réseau à deux clients : `./gradlew.bat exportClientLaunch -PclientSmoke`, puis `./gradlew.bat runGameTestServer -PnetworkSmoke` et `./tools/network_clients.ps1` ; variante redémarrage avec `-PnetworkReload` (voir `docs/VALIDATION.md`).

## Suite possible

Les cinq étapes initiales sont livrées (voir `docs/VALIDATION.md`). Pistes : expéditions, spécialistes et objets, attractivité, charrettes visibles sur les routes, IA rivale qui construit réellement, équilibrage sur des parties longues.

Licence GPL-3.0-only ; voir `LICENSE` et `NOTICE.md`. `build/distributions/annocraft1800-0.4.0-source-distribution.zip` contient les sources, tests, ressources, générateur, wrapper et scripts.
