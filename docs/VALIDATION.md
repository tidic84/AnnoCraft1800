# Résultats de validation — 7 octobre 2026

Environnement : Windows, Java 17, Minecraft 1.20.1, Forge 47.4.0.

## Version 0.2.0 — économie (boucle paysans–poisson)

| Vérification | Résultat |
| --- | --- |
| Compilation et JAR reobfusqué `annocraft1800-0.2.0.jar` | Réussi |
| JUnit : 10 tests (5 du socle, 5 de l’économie) | Réussi |
| Simulation de 15 minutes : 4 résidences à 9 habitants ou plus, pêcherie, bûcheron et scierie, au moins 36 paysans pour 30 postes, solde positif, planches produites | Réussi |
| Arrêts de production : sans route, sans main-d’œuvre, sans intrant, stockage plein | Réussi |
| Minecraft GameTest : 6 scénarios côté serveur, dont coûts, routes et migration v1 → v2 | Réussi |
| Client graphique (français, échelle d’interface 3) : construction, amélioration, route par paquet réseau, réception de l’état économique, dix bascules | Réussi |
| Deux clients graphiques en TCP, protocole 2 | Réussi |
| Redémarrage, comparaison complète de la sauvegarde v2 (routes comprises) et reconnexion des deux clients | Réussi |
| Tickets de caméra après fermeture des deux vues | Zéro |

Le banc réseau a été lancé avec `tools/network_clients.ps1`, portage PowerShell du script Python précédent. Le premier passage a migré la sauvegarde v1 laissée par le socle ; le second a comparé la sauvegarde v2 complète après redémarrage. La capture `run-client-smoke/screenshots/annocraft-rts.png` montre le catalogue, le panneau d’île et le panneau de bâtiment sans débordement à 1280 × 720, échelle 3.

L’équilibrage (coûts, cycles, impôts, besoins) est vérifié par la simulation JUnit, pas par une partie prolongée. La boucle complète sans bac à sable — comptoir, routes, maisons, pêcherie — reste à jouer en conditions réelles (voir `ACCEPTANCE.md`).

## Version 0.1.0 — socle

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

Les tests réseau utilisent le banc isolé `networkSmoke`, lié uniquement à l’interface locale. Les snapshots, les blocs, la sélection et la restauration de la caméra ont été vérifiés dans les deux renderers Minecraft.

Le contrôle de reprise compare l’intégralité de `ColonyData.save` avec la sauvegarde précédente : identifiants, révision, îles, positions, rotations, définitions améliorées, dimensions, blocs d’origine et, depuis la version 2, routes et économie.

La session prolongée sur un serveur Forge normal avec comptes authentifiés, décrite dans `ACCEPTANCE.md`, reste à effectuer. Ces essais ne constituent pas un benchmark de performance ni une validation du futur contenu maritime ou narratif.
