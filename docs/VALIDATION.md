# Résultats de validation — 7 octobre 2026

Environnement : Windows, Java 17, Minecraft 1.20.1, Forge 47.4.0.

| Vérification | Résultat |
| --- | --- |
| Compilation et JAR reobfusqué | Réussi |
| JUnit : 5 tests, dont géométrie sur trois graines | Réussi |
| Minecraft GameTest : 5 scénarios côté serveur | Réussi |
| 100 bâtiments, deux sessions de caméra, déplacement et expiration | Réussi |
| Client graphique : monde neuf, construction et sélection par clic, amélioration et dix bascules de caméra | Réussi |
| Deux clients graphiques en TCP, présents simultanément dans l’archipel | Réussi |
| Redémarrage et comparaison complète de la sauvegarde | Réussi |
| Reconnexion des deux clients, sélection par clic et dix bascules chacun | Réussi |
| Tickets de caméra après fermeture des deux vues | Zéro |

Les tests réseau utilisent le banc isolé `networkSmoke`, lié uniquement à l’interface locale. Les clients AnnoTesterB et AnnoTesterA étaient connectés simultanément du 01:24:55 au 01:25:14, heure de Paris. Les snapshots, les blocs, la sélection et la restauration de la caméra ont été vérifiés dans les deux renderers Minecraft.

Le contrôle de reprise compare l’intégralité de `ColonyData.save` avec la sauvegarde précédente : identifiants, révision, îles, positions, rotations, définitions améliorées, dimensions et blocs d’origine.

Les GameTests avec faux joueurs vérifient aussi le placement concurrent, les quatre rotations d’un entrepôt rectangulaire, le comptoir côtier, la protection des blocs, l’amélioration/démolition et le refus d’une version de sauvegarde inconnue. Le test de 100 constructions utilise deux abonnements de caméra et vérifie leur limite ainsi que leur libération explicite et leur expiration.

La session prolongée sur un serveur Forge normal avec comptes authentifiés, décrite dans `ACCEPTANCE.md`, reste à effectuer. Ces essais ne constituent pas un benchmark de performance ni une validation du futur contenu économique, maritime ou narratif.
