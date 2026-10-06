# Third-party notices

AnnoCraft1800 is licensed under GPL-3.0-only; see LICENSE. Source releases must include the build scripts, resources and this notice.

## Reign of the Nether

Upstream: https://github.com/SoLegendary/reignofnether
Author: SoLegendary and contributors. License: GPL-3.0.
Audited revision: `8c99c37ab2be933357151b3be8640e82efd63766` (1.20.1-dev).

Camera limits and rotation-relative panning in `client/CameraMath.java` are adapted from `orthoview/OrthoviewClientEvents.java` and `util/MyMath.java`, modified for AnnoCraft1800 on 2026-10-07. AnnoCraft uses a separate camera anchor rather than moving the player's body.

Dependency audit: upstream camera events reference units, factions, fog of war, start positions, tutorials, HUD and player packets. BuildingPlacement references alliances, combat, abilities, research, resources and worker units. These event classes are not imported. Selection, previews, building lifecycle, network and persistence are implemented independently. No RoN runtime dependency or upstream assets are bundled.

Minecraft/Forge and their development tools retain their own licenses. Generated demonstration structures and narrative material are original; no Anno 1800 game assets are included. AnnoCraft1800 is an unofficial project.
