# Validation du socle

## Automatique

- `test` : géométrie sur graines 0, 1800 et -719331, plateaux, côtes, déterminisme et mathématiques de caméra.
- `runGameTestServer` : serveur Minecraft avec constructions, concurrence sur une emprise, rotations, amélioration/démolition, sauvegarde versionnée, 100 bâtiments et deux sessions de tickets.
- `build` : compilation et création du JAR distribué.
- `runClient -PclientSmoke` : renderer Minecraft et serveur intégré, clics/selection, construction et amélioration, dix bascules RTS/visite.
- Banc `networkSmoke` : deux clients graphiques de développement reliés en TCP à un serveur physique distinct ; snapshots, blocs et sortie des caméras.
- Banc `networkSmoke + networkReload` : comparaison complète de la sauvegarde après arrêt/redémarrage et reconnexion des deux clients.

## Session à deux clients authentifiés

1. Démarrer un serveur Forge 47.4.0 avec le JAR, connecter deux clients équipés du même JAR et exécuter `/anno join` sur chacun.
2. Alterner dix fois F6/vue visite. Le personnage doit rester au même endroit et retrouver son orientation ; aucune touche ne doit rester active après fermeture.
3. Sur A, placer les trois bâtiments, faire les quatre rotations et améliorer une résidence. B doit voir les blocs et le panneau de sélection corrects, y compris lorsque sa caméra est loin de son personnage.
4. Construire simultanément sur la même emprise : une seule construction doit réussir. Tester mer, pente, limites, volume obstrué et comptoir dans les terres.
5. En visite, essayer de casser, placer, utiliser un seau et déclencher une explosion sur les constructions : la structure doit rester intacte.
6. Avec 100 constructions, déplacer les deux caméras pendant cinq minutes. Contrôler `/anno status` : les tickets restent bornés par joueur, diminuent après sortie/déconnexion et reviennent à zéro lorsque les deux caméras sont fermées.
7. Arrêter proprement, redémarrer et reconnecter : mêmes identifiants, positions, rotations, niveaux et nombre de bâtiments. Démolir une résidence améliorée et vérifier la restauration complète du terrain.
8. Tester `/anno leave`, fermeture du menu, mort et changement de dimension : aucune caméra ni abonnement ne doit subsister.

## Économie (0.2.0)

Sur un monde neuf, sans bac à sable (`/anno status` ne doit pas afficher `sandbox`) :

1. Construire un comptoir côtier : 500 or prélevés, 30 planches et 10 poissons dans le panneau de l’île. Une résidence sur une autre île est refusée faute de matériaux.
2. Tracer une route depuis le comptoir, construire quatre résidences le long de cette route puis une pêcherie, une cabane de bûcheron et une scierie reliées. Sur A et B, les panneaux doivent afficher les mêmes stocks, le même trésor et le même statut pour chaque bâtiment.
3. Laisser tourner dix minutes : les maisons atteignent 10/10, la main-d’œuvre paysanne couvre 30 postes, le stock de planches augmente et le solde par minute devient positif.
4. Retirer une portion de route : les bâtiments isolés passent « Hors réseau routier », leur production s’arrête et la population de leurs maisons retombe à un habitant. Reconstruire la route rétablit l’activité.
5. Démolir la pêcherie : le poisson s’épuise, les maisons affichent « Besoins non satisfaits » et leur population tombe à cinq.
6. Améliorer une maison pleine et approvisionnée en résidence d’ouvriers : 100 or et 4 planches prélevés, capacité 20, main-d’œuvre paysanne réduite.
7. Arrêter, redémarrer et reconnecter : trésor, stocks, routes, habitants et cycles identiques à l’arrêt (à quelques secondes de simulation près).

Le jalon ne doit être déclaré entièrement accepté qu’après cette session réseau et visuelle. Les GameTests utilisent des faux joueurs et couvrent la logique serveur, pas le rendu ou le transport réseau réel.
