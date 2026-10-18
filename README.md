# MerlinGuard

Minecraft Paper plugin source project for TPA requests, player waypoints, performance warnings, and conservative anti-cheat indicators.

## Build on a phone with GitHub Codespaces

1. Create a GitHub repository and upload the **contents** of this folder (including hidden folders `.devcontainer` and `.github`).
2. On the repository page, tap **Code → Codespaces → Create codespace on main**.
3. Wait for the environment to finish setting up. It is configured to use Java 25 and Maven.
4. Open **Terminal → New Terminal** and run `./build.sh`.
5. If the build succeeds, the plugin JAR is in `target/`.

Alternatively, push the repository to GitHub and open **Actions → Build MerlinGuard**. The workflow builds the project and exposes the resulting JAR as an artifact only if the build succeeds. Download the artifact from the completed workflow run.

## Important version note

The requested Minecraft/Paper version is `26.3`. The Paper API version currently written in `pom.xml` must exist in the official Paper Maven repository for the build to succeed. If Maven reports that it cannot resolve `io.papermc.paper:paper-api`, verify the official version and update the dependency to a published Paper API version; do not guess a version. A successful Maven build verifies compilation and packaging, but does not replace testing on an actual Paper server.

## Commands

- `/tpa` opens the player-head teleport GUI
- `/tpa <player>`
- `/tpaccept`
- `/tpdeny`
- `/tpahere <player>`
- `/tpacancel [player]`
- `/tpatoggle`
- `/waypoint set <name>`
- `/waypoint list`
- `/waypoint tp <name>`
- `/waypoint delete <name>`
- `/merlinguard status`
- `/merlinguard reload`

See `src/main/resources/config.yml` for settings and `src/main/resources/plugin.yml` for commands and permissions.

## Limits

Server-side plugins cannot reliably detect every Freecam implementation. X-Ray detection based on mining statistics is only a heuristic; use Paper's built-in Anti-Xray configuration for stronger protection. Do not treat heuristic evidence alone as proof of cheating.
