package de.merlin.merlinguard;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class MerlinGuard extends JavaPlugin implements Listener {
    private final Map<UUID, UUID> requests = new ConcurrentHashMap<>(); // target -> requester
    private final Map<UUID, Long> lastRequest = new ConcurrentHashMap<>();
    private final Map<UUID, Location> pendingTeleport = new ConcurrentHashMap<>();
    private final Map<UUID, Long> teleportStart = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> reachStrikes = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Location>> waypoints = new HashMap<>();
    private final Map<UUID, Integer> oreBreaks = new HashMap<>();
    private final Map<UUID, Integer> totalStoneBreaks = new HashMap<>();
    private File waypointFile;
    private long lastPerformanceWarning;
    private final Map<UUID, UUID> guiSelectedPlayer = new ConcurrentHashMap<>();
    private final Set<UUID> tpaDisabled = ConcurrentHashMap.newKeySet();
    private static final String GUI_TITLE = "§1✦ MerlinGuard §8| §bTeleport";

    @Override public void onEnable() {
        saveDefaultConfig();
        waypointFile = new File(getDataFolder(), "waypoints.yml");
        loadWaypoints();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("tpa")).setExecutor(this);
        Objects.requireNonNull(getCommand("tpaccept")).setExecutor(this);
        Objects.requireNonNull(getCommand("tpdeny")).setExecutor(this);
        Objects.requireNonNull(getCommand("tpahere")).setExecutor(this);
        Objects.requireNonNull(getCommand("tpacancel")).setExecutor(this);
        Objects.requireNonNull(getCommand("tpatoggle")).setExecutor(this);
        Objects.requireNonNull(getCommand("waypoint")).setExecutor(this);
        Objects.requireNonNull(getCommand("merlinguard")).setExecutor(this);
        Bukkit.getScheduler().runTaskTimer(this, this::processWarmups, 20L, 5L);
        Bukkit.getScheduler().runTaskTimer(this, this::checkPerformance, 20L * 30, 20L * 30);
        getLogger().info("MerlinGuard enabled.");
    }

    @Override public void onDisable() { saveWaypoints(); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("merlinguard")) return adminCommand(sender, args);
        if (!(sender instanceof Player player)) { sender.sendMessage("Dieser Befehl ist nur im Spiel verfügbar."); return true; }
        switch (cmd) {
            case "tpa" -> {
                if (args.length == 0) { openTpaGui(player); return true; }
                if (args.length != 1) { player.sendMessage("§bNutzung: §f/tpa [Spieler]"); return true; }
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null || target.equals(player)) { player.sendMessage("§cSpieler nicht gefunden oder ungültig."); return true; }
                long now = System.currentTimeMillis();
                long cooldown = getConfig().getLong("teleport.cooldown-seconds", 5) * 1000L;
                if (now - lastRequest.getOrDefault(player.getUniqueId(), 0L) < cooldown) { player.sendMessage("§cBitte warte kurz, bevor du erneut eine Anfrage sendest."); return true; }
                requests.put(target.getUniqueId(), player.getUniqueId());
                lastRequest.put(player.getUniqueId(), now);
                player.sendMessage("§aTPA-Anfrage an §f" + target.getName() + " §agesendet.");
                target.sendMessage("§b" + player.getName() + " §7möchte sich zu dir teleportieren. §f/tpaccept §7oder §f/tpdeny");
                long expiry = getConfig().getLong("teleport.request-expiry-seconds", 60) * 1000L;
                Bukkit.getScheduler().runTaskLater(this, () -> requests.remove(target.getUniqueId(), player.getUniqueId()), Math.max(20, expiry / 50));
            }
            case "tpaccept" -> {
                UUID requesterId = requests.remove(player.getUniqueId());
                if (requesterId == null) { player.sendMessage("§cDu hast keine offene TPA-Anfrage."); return true; }
                Player requester = Bukkit.getPlayer(requesterId);
                if (requester == null) { player.sendMessage("§cDer Spieler ist nicht mehr online."); return true; }
                pendingTeleport.put(requesterId, player.getLocation().clone());
                teleportStart.put(requesterId, System.currentTimeMillis());
                requester.sendMessage("§aTeleport startet in " + getConfig().getInt("teleport.warmup-seconds", 3) + " Sekunden. Bewege dich nicht.");
                player.sendMessage("§aDu hast die TPA-Anfrage angenommen.");
            }
            case "tpdeny" -> {
                UUID requesterId = requests.remove(player.getUniqueId());
                if (requesterId == null) { player.sendMessage("§cDu hast keine offene TPA-Anfrage."); return true; }
                Player requester = Bukkit.getPlayer(requesterId);
                if (requester != null) requester.sendMessage("§cDeine TPA-Anfrage wurde abgelehnt.");
                player.sendMessage("§7Anfrage abgelehnt.");
            }
            case "tpahere" -> {
                if (args.length != 1) { player.sendMessage("§bNutzung: §f/tpahere <Spieler>"); return true; }
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null || target.equals(player)) { player.sendMessage("§cSpieler nicht gefunden oder ungültig."); return true; }
                if (tpaDisabled.contains(target.getUniqueId())) { player.sendMessage("§cDieser Spieler empfängt keine TPA-Anfragen."); return true; }
                requests.put(player.getUniqueId(), target.getUniqueId());
                player.sendMessage("§b✦ §fTPA-HERE-Anfrage an §b" + target.getName() + " §7gesendet.");
                target.sendMessage("§b✦ §f" + player.getName() + " §7möchte, dass du dich zu ihm teleportierst. §a/tpaccept §7oder §c/tpdeny");
            }
            case "tpacancel" -> {
                boolean removed = false;
                for (var e : new ArrayList<>(requests.entrySet())) {
                    if (e.getValue().equals(player.getUniqueId()) && (args.length == 0 || (Bukkit.getPlayer(e.getKey()) != null && Bukkit.getPlayer(e.getKey()).getName().equalsIgnoreCase(args[0])))) {
                        requests.remove(e.getKey(), e.getValue()); removed = true;
                        Player target = Bukkit.getPlayer(e.getKey()); if (target != null) target.sendMessage("§cDie TPA-Anfrage wurde zurückgezogen.");
                    }
                }
                player.sendMessage(removed ? "§aAnfrage(n) zurückgezogen." : "§cKeine passende ausgehende Anfrage gefunden.");
            }
            case "tpatoggle" -> {
                UUID id = player.getUniqueId();
                if (tpaDisabled.remove(id)) player.sendMessage("§a✦ Du empfängst wieder TPA-Anfragen.");
                else { tpaDisabled.add(id); player.sendMessage("§c✦ TPA-Anfragen deaktiviert."); }
            }
            case "waypoint" -> handleWaypoint(player, args);
            default -> { return false; }
        }
        return true;
    }

    private ItemStack guiItem(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(Arrays.asList(loreLines));
        item.setItemMeta(meta);
        return item;
    }

    private void openTpaGui(Player viewer) {
        Inventory inv = Bukkit.createInventory(null, 54, GUI_TITLE);
        for (int i = 0; i < 54; i++) inv.setItem(i, guiItem(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "§8 "));
        int slot = 10;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(viewer) || online.hasMetadata("vanished") || tpaDisabled.contains(online.getUniqueId())) continue;
            while (slot < 44 && (slot % 9 == 8 || slot % 9 == 0)) slot++;
            if (slot >= 44) break;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(online);
            meta.setDisplayName("§b✦ §f" + online.getName());
            meta.setLore(Arrays.asList("§7Klicke, um Teleportoptionen", "§7für diesen Spieler zu öffnen.", "", "§8MerlinGuard §7• §bTPA"));
            head.setItemMeta(meta);
            inv.setItem(slot++, head);
        }
        inv.setItem(45, guiItem(Material.BOOK, "§b✦ §fTeleport-Menü", "§7Wähle einen Spieler aus."));
        inv.setItem(49, guiItem(Material.BARRIER, "§cMenü schließen", "§7Klicken zum Schließen"));
        inv.setItem(53, guiItem(Material.ENDER_PEARL, "§bAnfragen", "§7Nutze §f/tpaccept §7oder §f/tpdeny"));
        viewer.openInventory(inv);
    }

    private void openPlayerActions(Player viewer, Player target) {
        Inventory inv = Bukkit.createInventory(null, 27, "§1✦ MerlinGuard §8| §b" + target.getName());
        for (int i = 0; i < 27; i++) inv.setItem(i, guiItem(Material.BLUE_STAINED_GLASS_PANE, "§8 "));
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) head.getItemMeta(); sm.setOwningPlayer(target);
        sm.setDisplayName("§b" + target.getName()); sm.setLore(Arrays.asList("§7Spieler auswählen")); head.setItemMeta(sm);
        inv.setItem(13, head);
        inv.setItem(11, guiItem(Material.ENDER_PEARL, "§b✦ §fTPA", "§7Teleportiere dich zu " + target.getName(), "§eKlicken zum Senden"));
        inv.setItem(15, guiItem(Material.ENDER_EYE, "§b✦ §fTPA HERE", "§7Bitte " + target.getName() + ", zu dir zu kommen", "§eKlicken zum Senden"));
        inv.setItem(18, guiItem(Material.ARROW, "§7← Zurück", "§7Zur Spielerliste"));
        inv.setItem(26, guiItem(Material.BARRIER, "§cSchließen"));
        guiSelectedPlayer.put(viewer.getUniqueId(), target.getUniqueId());
        viewer.openInventory(inv);
    }

    @EventHandler public void onGuiClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        String title = event.getView().getTitle();
        if (!title.startsWith("§1✦ MerlinGuard §8| §b")) return;
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        if (title.equals(GUI_TITLE)) {
            if (slot == 49) { viewer.closeInventory(); return; }
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() != Material.PLAYER_HEAD) return;
            String name = clicked.getItemMeta().getDisplayName().replace("§b✦ §f", "");
            Player target = Bukkit.getPlayerExact(name);
            if (target != null && !target.equals(viewer)) openPlayerActions(viewer, target);
            return;
        }
        UUID targetId = guiSelectedPlayer.get(viewer.getUniqueId());
        Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
        if (slot == 18) { guiSelectedPlayer.remove(viewer.getUniqueId()); openTpaGui(viewer); return; }
        if (slot == 26) { guiSelectedPlayer.remove(viewer.getUniqueId()); viewer.closeInventory(); return; }
        if (target == null) { viewer.closeInventory(); viewer.sendMessage("§cDieser Spieler ist nicht mehr online."); return; }
        if (slot == 11) { viewer.closeInventory(); viewer.performCommand("tpa " + target.getName()); }
        if (slot == 15) { viewer.closeInventory(); viewer.performCommand("tpahere " + target.getName()); }
    }

    @EventHandler public void onGuiDrag(InventoryDragEvent event) {
        if (event.getView().getTitle().startsWith("§1✦ MerlinGuard §8| §b")) event.setCancelled(true);
    }

    private void processWarmups() {
        long now = System.currentTimeMillis();
        for (var entry : new ArrayList<>(pendingTeleport.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) { pendingTeleport.remove(entry.getKey()); teleportStart.remove(entry.getKey()); continue; }
            long delay = getConfig().getLong("teleport.warmup-seconds", 3) * 1000L;
            if (now - teleportStart.getOrDefault(entry.getKey(), now) >= delay) {
                Location destination = entry.getValue();
                pendingTeleport.remove(entry.getKey()); teleportStart.remove(entry.getKey());
                player.teleportAsync(destination).thenAccept(ok -> Bukkit.getScheduler().runTask(this, () -> player.sendMessage(ok ? "§aTeleportiert!" : "§cTeleport fehlgeschlagen.")));
            }
        }
    }

    @EventHandler public void onMove(PlayerMoveEvent event) {
        if (!getConfig().getBoolean("teleport.cancel-on-move", true) || event.getTo() == null) return;
        UUID id = event.getPlayer().getUniqueId();
        if (!pendingTeleport.containsKey(id)) return;
        Location from = event.getFrom(), to = event.getTo();
        if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()) {
            pendingTeleport.remove(id); teleportStart.remove(id);
            event.getPlayer().sendMessage("§cTeleport abgebrochen: Du hast dich bewegt.");
        }
    }

    private void handleWaypoint(Player player, String[] args) {
        if (args.length < 1) { player.sendMessage("§b/waypoint <set|list|tp|delete> [Name]"); return; }
        UUID id = player.getUniqueId();
        Map<String, Location> map = waypoints.computeIfAbsent(id, k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "set" -> {
                if (args.length != 2) { player.sendMessage("§c/waypoint set <Name>"); return; }
                int max = getConfig().getInt("waypoints.max-per-player", 20);
                if (!map.containsKey(args[1].toLowerCase(Locale.ROOT)) && map.size() >= max) { player.sendMessage("§cDu hast das Wegpunkt-Limit erreicht."); return; }
                map.put(args[1], player.getLocation().clone()); saveWaypoints(); player.sendMessage("§aWegpunkt §f" + args[1] + " §agespeichert.");
            }
            case "list" -> player.sendMessage(map.isEmpty() ? "§7Du hast keine Wegpunkte." : "§bWegpunkte: §f" + String.join("§7, §f", map.keySet()));
            case "tp" -> {
                if (args.length != 2) { player.sendMessage("§c/waypoint tp <Name>"); return; }
                Location loc = map.get(args[1]);
                if (loc == null || loc.getWorld() == null) { player.sendMessage("§cWegpunkt nicht gefunden."); return; }
                pendingTeleport.put(id, loc.clone()); teleportStart.put(id, System.currentTimeMillis());
                player.sendMessage("§aTeleport startet in " + getConfig().getInt("teleport.warmup-seconds", 3) + " Sekunden. Bewege dich nicht.");
            }
            case "delete" -> {
                if (args.length != 2) { player.sendMessage("§c/waypoint delete <Name>"); return; }
                if (map.remove(args[1]) == null) player.sendMessage("§cWegpunkt nicht gefunden."); else { saveWaypoints(); player.sendMessage("§aWegpunkt gelöscht."); }
            }
            default -> player.sendMessage("§b/waypoint <set|list|tp|delete> [Name]");
        }
    }

    @EventHandler public void onBlockBreak(BlockBreakEvent event) {
        Material type = event.getBlock().getType();
        UUID id = event.getPlayer().getUniqueId();
        if (type == Material.STONE || type == Material.DEEPSLATE || type == Material.NETHERRACK) totalStoneBreaks.merge(id, 1, Integer::sum);
        if (type.name().endsWith("_ORE") || type == Material.ANCIENT_DEBRIS) {
            int ores = oreBreaks.merge(id, 1, Integer::sum);
            int stone = totalStoneBreaks.getOrDefault(id, 0);
            if (getConfig().getBoolean("anti-cheat.xray-suspicion-log", true) && ores >= 12 && ores * 3 > Math.max(1, stone)) {
                getLogger().warning("Possible X-Ray indicator: " + event.getPlayer().getName() + " ores=" + ores + " stone-like blocks=" + stone + ". Not proof; no automatic punishment.");
                oreBreaks.put(id, 0); totalStoneBreaks.put(id, 0);
            }
        }
    }

    @EventHandler public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || event.getPlayer().hasPermission("merlinguard.bypass.reach")) return;
        double max = getConfig().getDouble("anti-cheat.block-interaction-max-distance", 7.0);
        if (event.getPlayer().getEyeLocation().distanceSquared(event.getClickedBlock().getLocation().add(.5, .5, .5)) <= max * max) return;
        Player player = event.getPlayer();
        Deque<Long> strikes = reachStrikes.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        long now = System.currentTimeMillis(), window = getConfig().getLong("anti-cheat.reach-strike-window-seconds", 20) * 1000L;
        while (!strikes.isEmpty() && now - strikes.peekFirst() > window) strikes.removeFirst();
        strikes.addLast(now);
        getLogger().warning("Reach violation candidate from " + player.getName() + " (" + strikes.size() + " strikes)");
        if (strikes.size() >= getConfig().getInt("anti-cheat.reach-strikes-before-kick", 3)) {
            String reason = getConfig().getString("anti-cheat.kick-reason", "Unzulässige Interaktionsreichweite (MerlinGuard)");
            player.kickPlayer("§c" + reason);
            strikes.clear();
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pendingTeleport.remove(id); teleportStart.remove(id); requests.remove(id); reachStrikes.remove(id);
        requests.values().removeIf(id::equals);
    }

    private boolean adminCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("merlinguard.admin")) { sender.sendMessage("§cKeine Berechtigung."); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) { reloadConfig(); sender.sendMessage("§aMerlinGuard-Konfiguration neu geladen."); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            double tps = Bukkit.getTPS()[0];
            sender.sendMessage("§bMerlinGuard §7| §fTPS: " + String.format(Locale.US, "%.2f", tps) + " §7| §fOnline: " + Bukkit.getOnlinePlayers().size());
            return true;
        }
        sender.sendMessage("§b/mg status §7| §b/mg reload"); return true;
    }

    private void checkPerformance() {
        double tps = Bukkit.getTPS()[0];
        long now = System.currentTimeMillis();
        long interval = getConfig().getLong("performance.warning-interval-seconds", 60) * 1000L;
        if (tps < getConfig().getDouble("performance.warn-below-tps", 18.0) && now - lastPerformanceWarning >= interval) {
            lastPerformanceWarning = now;
            getLogger().warning(String.format(Locale.US, "Low TPS detected: %.2f. MerlinGuard does not automatically delete entities/items.", tps));
            for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("merlinguard.admin")) p.sendMessage("§c[MerlinGuard] Niedrige TPS erkannt: " + String.format(Locale.US, "%.2f", tps));
        }
    }

    private void loadWaypoints() {
        if (!waypointFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(waypointFile);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) return;
        for (String uuidText : players.getKeys(false)) {
            UUID id; try { id = UUID.fromString(uuidText); } catch (IllegalArgumentException ignored) { continue; }
            ConfigurationSection section = players.getConfigurationSection(uuidText);
            if (section == null) continue;
            Map<String, Location> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (String name : section.getKeys(false)) {
                Location loc = section.getLocation(name);
                if (loc != null) map.put(name, loc);
            }
            waypoints.put(id, map);
        }
    }

    private void saveWaypoints() {
        if (waypointFile == null) return;
        YamlConfiguration yaml = new YamlConfiguration();
        for (var playerEntry : waypoints.entrySet()) for (var wp : playerEntry.getValue().entrySet())
            yaml.set("players." + playerEntry.getKey() + "." + wp.getKey(), wp.getValue());
        try { if (!getDataFolder().exists()) getDataFolder().mkdirs(); yaml.save(waypointFile); }
        catch (IOException e) { getLogger().severe("Could not save waypoints: " + e.getMessage()); }
    }
}
