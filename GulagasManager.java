package com.kodari.skinoutfitswapper;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

public final class GulagasManager implements Listener {
    private static final String ROOT = "gulagas";
    private static final int MAX_OBJECTIVE = 10000;
    private static final List<String> ZONE_BLOCKS = List.of(
            "COAL_ORE", "DEEPSLATE_COAL_ORE", "DEEPSLATE",
            "OAK_LOG", "STRIPPED_OAK_LOG", "SPRUCE_LOG", "STRIPPED_SPRUCE_LOG",
            "BIRCH_LOG", "STRIPPED_BIRCH_LOG", "JUNGLE_LOG", "STRIPPED_JUNGLE_LOG",
            "ACACIA_LOG", "STRIPPED_ACACIA_LOG", "DARK_OAK_LOG", "STRIPPED_DARK_OAK_LOG",
            "MANGROVE_LOG", "STRIPPED_MANGROVE_LOG", "CHERRY_LOG", "STRIPPED_CHERRY_LOG",
            "PALE_OAK_LOG", "STRIPPED_PALE_OAK_LOG", "CRIMSON_STEM", "STRIPPED_CRIMSON_STEM",
            "WARPED_STEM", "STRIPPED_WARPED_STEM");
    private final SkinOutfitSwapper plugin;
    private final Map<UUID, Selection> selections = new HashMap<>();
    private final Map<UUID, Zone> zoneDrafts = new HashMap<>();
    private final Map<String, Map<String, BlockSnapshot>> depletedBlocks = new HashMap<>();

    public GulagasManager(SkinOutfitSwapper plugin) {
        this.plugin = plugin;
    }

    public boolean handleCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("gulagas.admin")) {
            sender.sendMessage("§cNeturi teisės naudoti šios komandos.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§eNaudojimas: /gulagas <zaidejas>, /gulagas nuimti <zaidejas>, /gulagas spawn arba /gulagas zona <pavadinimas>");
            return true;
        }
        if (args[0].equalsIgnoreCase("spawn")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cGulago spawn vietą gali nustatyti tik žaidėjas.");
                return true;
            }
            if (args.length != 1) {
                player.sendMessage("§eNaudojimas: /gulagas spawn");
                return true;
            }
            Location location = player.getLocation();
            plugin.getConfig().set(ROOT + ".spawn.world", location.getWorld().getName());
            plugin.getConfig().set(ROOT + ".spawn.x", location.getX());
            plugin.getConfig().set(ROOT + ".spawn.y", location.getY());
            plugin.getConfig().set(ROOT + ".spawn.z", location.getZ());
            plugin.getConfig().set(ROOT + ".spawn.yaw", location.getYaw());
            plugin.getConfig().set(ROOT + ".spawn.pitch", location.getPitch());
            plugin.saveConfig();
            player.sendMessage("§aGulago spawn vieta nustatyta.");
            return true;
        }
        if (args[0].equalsIgnoreCase("nuimti") || args[0].equalsIgnoreCase("remove")) {
            if (args.length != 2 || args[1].isBlank()) {
                sender.sendMessage("§eNaudojimas: /gulagas nuimti <zaidejas>");
                return true;
            }
            removePunishment(sender, args[1]);
            return true;
        }
        if (args[0].equalsIgnoreCase("zona")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cZonas gali kurti tik žaidėjas.");
                return true;
            }
            if (args.length != 2 || args[1].isBlank()) {
                player.sendMessage("§eNaudojimas: /gulagas zona <pavadinimas>");
                return true;
            }
            handleZoneCommand(player, args[1]);
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cBausmę per GUI gali paskirti tik žaidėjas serveryje.");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            player.sendMessage("§cŽaidėjas turi būti prisijungęs.");
            return true;
        }
        openPunishmentGui(player, target);
        return true;
    }

    private void removePunishment(CommandSender sender, String playerName) {
        UUID playerId = Bukkit.getOfflinePlayer(playerName).getUniqueId();
        String id = playerId.toString();
        String prisonerPath = ROOT + ".prisoners." + id;
        if (!plugin.getConfig().contains(prisonerPath)) {
            sender.sendMessage("§eŽaidėjas §f" + playerName + "§e neturi aktyvios gulago bausmės.");
            return;
        }

        String storedName = plugin.getConfig().getString(prisonerPath + ".name", playerName);
        plugin.getConfig().set(prisonerPath, null);
        Player target = Bukkit.getPlayer(playerId);
        if (target == null) {
            plugin.getConfig().set(ROOT + ".active-texture-values." + id, null);
            plugin.getConfig().set(ROOT + ".active-texture-signatures." + id, null);
            plugin.getConfig().set(ROOT + ".active-skins." + id, null);
            plugin.getConfig().set(ROOT + ".active-models." + id, null);
        }
        plugin.saveConfig();
        if (target != null) {
            plugin.restoreGulagSkin(target);
            target.sendMessage("§aTavo gulago bausmė nuimta.");
        }
        sender.sendMessage("§aŽaidėjui §f" + storedName + "§a gulago bausmė nuimta.");
    }

    private void handleZoneCommand(Player player, String name) {
        String key = zoneKey(name);
        ConfigurationSection saved = plugin.getConfig().getConfigurationSection(ROOT + ".zones." + key);
        if (saved != null) {
            openZoneGui(player, key, saved.getString("name", name),
                    configuredBlocks(saved, "spawn-blocks"), configuredBlocks(saved, "mine-blocks"),
                    saved.getInt("regen-percent", 70), true, BlockPickerMode.SPAWN);
            return;
        }

        Selection first = selections.get(player.getUniqueId());
        if (first == null || !first.key().equals(key)) {
            selections.put(player.getUniqueId(), new Selection(key, name, player.getWorld().getName(),
                    player.getLocation().getBlockX(), player.getLocation().getBlockY(),
                    player.getLocation().getBlockZ()));
            player.sendMessage("§aPažymėtas pirmas taškas zonai §f" + name + "§a. Nueik į priešingą kampą ir pakartok komandą.");
            return;
        }
        if (!first.world().equals(player.getWorld().getName())) {
            player.sendMessage("§cAntras taškas turi būti tame pačiame pasaulyje.");
            return;
        }
        if (first.x() == player.getLocation().getBlockX()
                && first.y() == player.getLocation().getBlockY()
                && first.z() == player.getLocation().getBlockZ()) {
            player.sendMessage("§cAntras taškas turi būti kitoje vietoje.");
            return;
        }
        selections.remove(player.getUniqueId());
        Set<String> defaults = defaultZoneBlocks(true, true);
        Zone draft = createZone(key, name, first.world(), first.x(), first.y(), first.z(),
                player.getLocation().getBlockX(), player.getLocation().getBlockY(),
                player.getLocation().getBlockZ(), defaults, defaults, 70, 0);
        if (!validateZone(player, draft)) {
            return;
        }
        zoneDrafts.put(player.getUniqueId(), draft);
        openZoneGui(player, draft.key(), draft.name(), draft.spawnBlocks(), draft.mineBlocks(),
                draft.regenPercent(), false, BlockPickerMode.SPAWN);
    }

    private void openPunishmentGui(Player admin, Player target) {
        String path = ROOT + ".prisoners." + target.getUniqueId();
        int coal = Math.max(0, plugin.getConfig().getInt(path + ".coal", 0)
                - plugin.getConfig().getInt(path + ".coal-done", 0));
        int wood = Math.max(0, plugin.getConfig().getInt(path + ".wood", 0)
                - plugin.getConfig().getInt(path + ".wood-done", 0));
        int deepslate = Math.max(0, plugin.getConfig().getInt(path + ".deepslate", 0)
                - plugin.getConfig().getInt(path + ".deepslate-done", 0));
        GuiHolder holder = new GuiHolder(GuiType.PUNISHMENT, target.getUniqueId(), target.getName(),
                null, null, Set.of(), Set.of(), 70, false, BlockPickerMode.SPAWN);
        holder.coalAmount = coal;
        holder.woodAmount = wood;
        holder.deepslateAmount = deepslate;
        Inventory inventory = Bukkit.createInventory(holder, 54, "Gulago bausmė: " + target.getName());
        holder.inventory = inventory;
        inventory.setItem(4, item("PAPER", "§eBausmė žaidėjui §f" + target.getName(),
                "§7Pasirink anglies, medžio ir deepslate kiekius."));
        addAmountControls(inventory, 19, 20, 21, 22, 23, 24, 25,
                "Anglies rūda", coal, "COAL_ORE");
        addAmountControls(inventory, 28, 29, 30, 31, 32, 33, 34,
                "Medžio rąstai", wood, "OAK_LOG");
        addAmountControls(inventory, 37, 38, 39, 40, 41, 42, 43,
                "Deepslate", deepslate, "DEEPSLATE");
        inventory.setItem(45, item("BARRIER", "§cAtšaukti", "§7Uždaryti nekeičiant bausmės."));
        inventory.setItem(49, item("EMERALD", "§aPaskirti bausmę",
                "§7Anglis: §f" + coal + "§7, mediena: §f" + wood + "§7, deepslate: §f" + deepslate));
        admin.openInventory(inventory);
    }

    private void addAmountControls(Inventory inventory, int minus64, int minus10, int minus1,
                                  int amountSlot, int plus1, int plus10, int plus64,
                                  String label, int amount, String icon) {
        inventory.setItem(minus64, item("REDSTONE", "§c-64 " + label, "§7Kairysis paspaudimas sumažina kiekį."));
        inventory.setItem(minus10, item("REDSTONE", "§c-10 " + label, "§7Sumažinti kiekį 10."));
        inventory.setItem(minus1, item("REDSTONE", "§c-1 " + label, "§7Sumažinti kiekį 1."));
        inventory.setItem(amountSlot, item(icon, "§e" + label + ": §f" + amount,
                "§7Nustatyk 0, jei šio darbo nereikia."));
        inventory.setItem(plus1, item("LIME_DYE", "§a+1 " + label, "§7Padidinti kiekį 1."));
        inventory.setItem(plus10, item("LIME_DYE", "§a+10 " + label, "§7Padidinti kiekį 10."));
        inventory.setItem(plus64, item("LIME_DYE", "§a+64 " + label, "§7Padidinti kiekį 64."));
    }

    private void openZoneGui(Player player, String key, String name, Set<String> spawnBlocks,
                             Set<String> mineBlocks, int regenPercent, boolean existing,
                             BlockPickerMode pickerMode) {
        GuiHolder holder = new GuiHolder(GuiType.ZONE, null, null, key, name,
                spawnBlocks, mineBlocks, Math.max(10, Math.min(100, regenPercent)), existing, pickerMode);
        Inventory inventory = Bukkit.createInventory(holder, 54, "Gulago zona: " + name);
        holder.inventory = inventory;
        List<String> availableBlocks = availableZoneBlocks();
        for (int slot = 0; slot < availableBlocks.size(); slot++) {
            String material = availableBlocks.get(slot);
            boolean selected = pickerMode == BlockPickerMode.SPAWN
                    ? holder.spawnBlocks.contains(material) : holder.mineBlocks.contains(material);
            String setting = pickerMode == BlockPickerMode.SPAWN ? "Atsiradimas" : "Kasimas";
            inventory.setItem(slot, item(material, "§e" + material.replace('_', ' '),
                    "§7" + setting + ": " + (selected ? "§aPasirinkta" : "§cNepasirinkta")));
        }
        inventory.setItem(45, item(pickerMode == BlockPickerMode.SPAWN ? "CHEST" : "IRON_PICKAXE",
                pickerMode == BlockPickerMode.SPAWN ? "§eRenkami atsirandantys blokai" : "§eRenkami kasami blokai",
                "§7Spustelėk, kad pakeistum pasirinkimą."));
        inventory.setItem(47, item("REDSTONE", "§c-5% atsinaujinimo riba", "§7Mažiausiai 10%."));
        inventory.setItem(48, item("CLOCK", "§eAtsinaujina iškasus " + holder.regenPercent + "%",
                "§7Zonos blokai atkuriami pasiekus šią ribą."));
        inventory.setItem(49, item("LIME_DYE", "§a+5% atsinaujinimo riba", "§7Daugiausiai 100%."));
        inventory.setItem(51, item("EMERALD", "§aIšsaugoti zoną", "§7Išsaugoti blokų pasirinkimus ir apsaugą."));
        inventory.setItem(53, item(existing ? "TNT" : "BARRIER",
                existing ? "§cIštrinti zoną" : "§cAtšaukti kūrimą",
                existing ? "§7Pašalinti šią gulago zoną." : "§7Neišsaugoti šios zonos."));
        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("gulagas.admin")) {
            return;
        }
        String sentencePath = ROOT + ".prisoners." + player.getUniqueId();
        boolean hasSentence = plugin.getConfig().contains(sentencePath);
        Zone zone = findZone(event.getBlock().getLocation());
        if (zone == null) {
            if (hasSentence) {
                event.setCancelled(true);
                player.sendMessage("§cBausmės metu gali kasti tik gulago darbo zonoje.");
            }
            return;
        }
        if (!hasSentence) {
            event.setCancelled(true);
            player.sendMessage("§cŠi gulago zona skirta tik žaidėjams, atliekantiems bausmę.");
            return;
        }

        String material = event.getBlock().getType().name();
        String objective = objectiveForMaterial(material);
        if (zone.mineBlocks().contains(material) && objective != null
                && remaining(sentencePath, objective) > 0) {
            recordProgress(player, zone, event.getBlock(), sentencePath, objective);
            return;
        }
        event.setCancelled(true);
        player.sendMessage("§cŠio bloko kasti negali — jis nepriklauso tavo aktyviai gulago užduočiai.");
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof GuiHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory() || event.getCurrentItem() == null) {
            return;
        }
        if (holder.type == GuiType.PUNISHMENT) {
            handlePunishmentClick((Player) event.getWhoClicked(), event.getRawSlot(), holder);
        } else {
            handleZoneClick((Player) event.getWhoClicked(), event.getRawSlot(), holder);
        }
    }

    private void handlePunishmentClick(Player admin, int slot, GuiHolder holder) {
        if (slot == 45) {
            admin.closeInventory();
            return;
        }
        String objective = slot >= 19 && slot <= 25 ? "coal"
                : slot >= 28 && slot <= 34 ? "wood"
                : slot >= 37 && slot <= 43 ? "deepslate" : null;
        if (objective == null) {
            if (slot == 49) {
                assignPunishment(admin, holder);
            }
            return;
        }
        int amount = switch (objective) {
            case "coal" -> holder.coalAmount;
            case "wood" -> holder.woodAmount;
            default -> holder.deepslateAmount;
        };
        int delta = switch (slot) {
            case 19, 28, 37 -> -64;
            case 20, 29, 38 -> -10;
            case 21, 30, 39 -> -1;
            case 23, 32, 41 -> 1;
            case 24, 33, 42 -> 10;
            case 25, 34, 43 -> 64;
            default -> 0;
        };
        int newAmount = Math.max(0, Math.min(MAX_OBJECTIVE, amount + delta));
        switch (objective) {
            case "coal" -> {
                holder.coalAmount = newAmount;
                addAmountControls(holder.inventory, 19, 20, 21, 22, 23, 24, 25,
                        "Anglies rūda", newAmount, "COAL_ORE");
            }
            case "wood" -> {
                holder.woodAmount = newAmount;
                addAmountControls(holder.inventory, 28, 29, 30, 31, 32, 33, 34,
                        "Medžio rąstai", newAmount, "OAK_LOG");
            }
            default -> {
                holder.deepslateAmount = newAmount;
                addAmountControls(holder.inventory, 37, 38, 39, 40, 41, 42, 43,
                        "Deepslate", newAmount, "DEEPSLATE");
            }
        }
        holder.inventory.setItem(49, item("EMERALD", "§aPaskirti bausmę", "§7Pasirinkta: §f"
                + holder.coalAmount + "§7 anglies rūdos, §f" + holder.woodAmount
                + "§7 rąstų, §f" + holder.deepslateAmount + "§7 deepslate."));
    }

    private void assignPunishment(Player admin, GuiHolder holder) {
        int coal = holder.coalAmount;
        int wood = holder.woodAmount;
        int deepslate = holder.deepslateAmount;
        if (coal == 0 && wood == 0 && deepslate == 0) {
            admin.sendMessage("§cPasirink bent vieną teigiamą užduoties kiekį.");
            return;
        }
        Location spawn = getGulagSpawn();
        if (spawn == null) {
            admin.sendMessage("§cPirmiausia nustatyk gulago spawn vietą komanda /gulagas spawn.");
            return;
        }
        Player target = Bukkit.getPlayer(holder.playerId);
        if (target == null) {
            admin.sendMessage("§cŽaidėjas atsijungė. Bausmė nepaskirta.");
            return;
        }
        String path = ROOT + ".prisoners." + holder.playerId;
        plugin.getConfig().set(path + ".name", holder.playerName);
        plugin.getConfig().set(path + ".coal", coal);
        plugin.getConfig().set(path + ".wood", wood);
        plugin.getConfig().set(path + ".deepslate", deepslate);
        plugin.getConfig().set(path + ".coal-done", 0);
        plugin.getConfig().set(path + ".wood-done", 0);
        plugin.getConfig().set(path + ".deepslate-done", 0);
        plugin.saveConfig();
        admin.closeInventory();
        admin.sendMessage("§aŽaidėjui §f" + holder.playerName + "§a paskirta bausmė: §f" + coal
                + "§a anglies rūdos, §f" + wood + "§a rąstų ir §f" + deepslate + "§a deepslate.");
        Bukkit.broadcastMessage("§eŽaidėjui §f" + holder.playerName + "§e buvo paskirta atlikti bausmę gulage.");
        target.sendMessage("§cTau paskirta gulago darbo bausmė. §7Anglies rūda: §f" + coal
                + "§7, medžio rąstai: §f" + wood + "§7, deepslate: §f" + deepslate
                + "§7. Kasimui naudok pažymėtas zonas.");
        plugin.applyGulagOutfit(target);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!target.isOnline() || !plugin.getConfig().contains(path)) {
                return;
            }
            if (!target.teleport(spawn)) {
                target.sendMessage("§cNepavyko teleportuoti į gulago spawn vietą. Pranešk administratoriui.");
                admin.sendMessage("§cŽaidėjo §f" + holder.playerName
                        + "§c teleportą į gulagą atšaukė kitas pluginas.");
            }
        });
    }

    private Location getGulagSpawn() {
        String path = ROOT + ".spawn";
        String worldName = plugin.getConfig().getString(path + ".world");
        if (worldName == null) {
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world,
                plugin.getConfig().getDouble(path + ".x"),
                plugin.getConfig().getDouble(path + ".y"),
                plugin.getConfig().getDouble(path + ".z"),
                (float) plugin.getConfig().getDouble(path + ".yaw"),
                (float) plugin.getConfig().getDouble(path + ".pitch"));
    }

    private void handleZoneClick(Player player, int slot, GuiHolder holder) {
        if (slot >= 0 && slot < availableZoneBlocks().size()) {
            String material = availableZoneBlocks().get(slot);
            Set<String> spawnBlocks = new LinkedHashSet<>(holder.spawnBlocks);
            Set<String> mineBlocks = new LinkedHashSet<>(holder.mineBlocks);
            if (holder.pickerMode == BlockPickerMode.SPAWN) {
                if (!spawnBlocks.add(material)) {
                    spawnBlocks.remove(material);
                } else {
                    mineBlocks.add(material);
                }
            } else if (!mineBlocks.add(material)) {
                mineBlocks.remove(material);
                spawnBlocks.remove(material);
            }
            openZoneGui(player, holder.zoneKey, holder.zoneName, spawnBlocks, mineBlocks,
                    holder.regenPercent, holder.existing, holder.pickerMode);
        } else if (slot == 45) {
            BlockPickerMode nextMode = holder.pickerMode == BlockPickerMode.SPAWN
                    ? BlockPickerMode.MINE : BlockPickerMode.SPAWN;
            openZoneGui(player, holder.zoneKey, holder.zoneName, holder.spawnBlocks, holder.mineBlocks,
                    holder.regenPercent, holder.existing, nextMode);
        } else if (slot == 47) {
            openZoneGui(player, holder.zoneKey, holder.zoneName, holder.spawnBlocks, holder.mineBlocks,
                    Math.max(10, holder.regenPercent - 5), holder.existing, holder.pickerMode);
        } else if (slot == 49) {
            openZoneGui(player, holder.zoneKey, holder.zoneName, holder.spawnBlocks, holder.mineBlocks,
                    Math.min(100, holder.regenPercent + 5), holder.existing, holder.pickerMode);
        } else if (slot == 51) {
            saveZone(player, holder);
        } else if (slot == 53) {
            if (holder.existing) {
                plugin.getConfig().set(ROOT + ".zones." + holder.zoneKey, null);
                plugin.getConfig().set(ROOT + ".zone-progress." + holder.zoneKey, null);
                depletedBlocks.remove(holder.zoneKey);
                plugin.saveConfig();
                player.sendMessage("§aGulago zona §f" + holder.zoneName + "§a ištrinta.");
            } else {
                zoneDrafts.remove(player.getUniqueId());
                player.sendMessage("§eZonos kūrimas atšauktas.");
            }
            player.closeInventory();
        }
    }

    private void saveZone(Player player, GuiHolder holder) {
        ConfigurationSection old = plugin.getConfig().getConfigurationSection(ROOT + ".zones." + holder.zoneKey);
        Zone zone;
        if (old != null) {
            Zone savedZone = readZone(holder.zoneKey, old);
            zone = new Zone(savedZone.key(), savedZone.name(), savedZone.world(),
                    savedZone.minX(), savedZone.minY(), savedZone.minZ(),
                    savedZone.maxX(), savedZone.maxY(), savedZone.maxZ(),
                    new LinkedHashSet<>(holder.spawnBlocks), new LinkedHashSet<>(holder.mineBlocks),
                    holder.regenPercent, savedZone.eligibleBlocks());
        } else {
            zone = zoneDrafts.get(player.getUniqueId());
            if (zone == null || !zone.key().equals(holder.zoneKey)) {
                player.closeInventory();
                player.sendMessage("§cZonos vietos žymėjimas neberastas. Sukurk ją iš naujo.");
                return;
            }
            zone = new Zone(zone.key(), zone.name(), zone.world(), zone.minX(), zone.minY(), zone.minZ(),
                    zone.maxX(), zone.maxY(), zone.maxZ(), new LinkedHashSet<>(holder.spawnBlocks),
                    new LinkedHashSet<>(holder.mineBlocks), holder.regenPercent, zone.eligibleBlocks());
        }
        if (holder.spawnBlocks.isEmpty() || holder.mineBlocks.isEmpty()) {
            player.sendMessage("§cPasirink bent vieną atsirandantį ir vieną kasamą bloką.");
            return;
        }
        if (!holder.mineBlocks.containsAll(holder.spawnBlocks)) {
            player.sendMessage("§cVisi atsirandantys blokai turi būti pažymėti ir kaip kasami.");
            return;
        }
        if (!validateZone(player, zone)) {
            return;
        }
        World world = Bukkit.getWorld(zone.world());
        int eligible = initializeZoneBlocks(world, zone);
        String path = ROOT + ".zones." + holder.zoneKey;
        plugin.getConfig().set(path + ".name", zone.name());
        plugin.getConfig().set(path + ".world", zone.world());
        plugin.getConfig().set(path + ".min-x", zone.minX());
        plugin.getConfig().set(path + ".min-y", zone.minY());
        plugin.getConfig().set(path + ".min-z", zone.minZ());
        plugin.getConfig().set(path + ".max-x", zone.maxX());
        plugin.getConfig().set(path + ".max-y", zone.maxY());
        plugin.getConfig().set(path + ".max-z", zone.maxZ());
        plugin.getConfig().set(path + ".coal", holder.mineBlocks.stream().anyMatch(this::isCoalOre));
        plugin.getConfig().set(path + ".wood", holder.mineBlocks.stream().anyMatch(this::isWoodLog));
        plugin.getConfig().set(path + ".spawn-blocks", new ArrayList<>(holder.spawnBlocks));
        plugin.getConfig().set(path + ".mine-blocks", new ArrayList<>(holder.mineBlocks));
        plugin.getConfig().set(path + ".regen-percent", holder.regenPercent);
        plugin.getConfig().set(path + ".eligible-blocks", eligible);
        plugin.getConfig().set(ROOT + ".zone-progress." + holder.zoneKey, null);
        plugin.saveConfig();
        depletedBlocks.remove(holder.zoneKey);
        zoneDrafts.remove(player.getUniqueId());
        player.closeInventory();
        player.sendMessage("§aGulago zona §f" + holder.zoneName + "§a išsaugota. Joje rasta §f"
                + eligible + "§a pasirinktų kasamų blokų; jie atsinaujins iš pasirinktų tipų pasiekus §f"
                + holder.regenPercent + "%§a kasimo ribą.");
    }

    private boolean validateZone(Player player, Zone zone) {
        World world = Bukkit.getWorld(zone.world());
        if (world == null) {
            player.sendMessage("§cZonos pasaulis nepasiekiamas.");
            return false;
        }
        long volume = (long) (zone.maxX() - zone.minX() + 1)
                * (zone.maxY() - zone.minY() + 1) * (zone.maxZ() - zone.minZ() + 1);
        int maximum = plugin.getConfig().getInt(ROOT + ".max-zone-blocks", 32768);
        if (volume > maximum) {
            player.sendMessage("§cZona per didelė. Maksimalus plotas: §f" + maximum + "§c blokų.");
            return false;
        }
        for (Zone other : loadZones()) {
            if (other.key().equals(zone.key()) || !other.world().equals(zone.world())) {
                continue;
            }
            if (overlaps(zone, other)) {
                player.sendMessage("§cZona persidengia su jau sukurta zona §f" + other.name() + "§c.");
                return false;
            }
        }
        return true;
    }

    private void recordProgress(Player player, Zone zone, Block block, String path, String objective) {
        int done = plugin.getConfig().getInt(path + "." + objective + "-done", 0) + 1;
        plugin.getConfig().set(path + "." + objective + "-done", done);
        int required = plugin.getConfig().getInt(path + "." + objective, 0);
        if (objective.equals("coal")) {
            player.sendMessage("§eGulago užduotis: anglies rūda §f" + Math.min(done, required) + "/" + required);
        } else if (objective.equals("wood")) {
            player.sendMessage("§eGulago užduotis: medžio rąstai §f" + Math.min(done, required) + "/" + required);
        } else {
            player.sendMessage("§eGulago užduotis: deepslate §f" + Math.min(done, required) + "/" + required);
        }

        Map<String, BlockSnapshot> depleted = depletedBlocks.computeIfAbsent(zone.key(), ignored -> new LinkedHashMap<>());
        String locationKey = block.getX() + ":" + block.getY() + ":" + block.getZ();
        depleted.putIfAbsent(locationKey, new BlockSnapshot(block.getLocation().clone(), block.getType().name()));
        int total = zone.eligibleBlocks();
        int threshold = Math.max(1, (int) Math.ceil(total * zone.regenPercent() / 100.0));
        if (total > 0 && depleted.size() >= threshold) {
            List<BlockSnapshot> restore = new ArrayList<>(depleted.values());
            List<String> spawnBlocks = zone.spawnBlocks().stream()
                    .filter(name -> Material.matchMaterial(name) != null).toList();
            depletedBlocks.remove(zone.key());
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (BlockSnapshot snapshot : restore) {
                    Block restoreBlock = snapshot.location().getBlock();
                    if (spawnBlocks.isEmpty()) {
                        continue;
                    }
                    String spawnName = spawnBlocks.get(ThreadLocalRandom.current().nextInt(spawnBlocks.size()));
                    Material material = Material.matchMaterial(spawnName);
                    if (material != null && restoreBlock.getType().isAir()) {
                        restoreBlock.setType(material, false);
                    }
                }
                Player online = Bukkit.getPlayer(player.getUniqueId());
                if (online != null) {
                    online.sendMessage("§aGulago zona §f" + zone.name() + "§a atsinaujino.");
                }
            });
        }

        if (hasCompletedSentence(path)) {
            plugin.getConfig().set(path, null);
            player.sendMessage("§aAtlikai visą gulago bausmę. Gali vėl kasti įprastai.");
            plugin.restoreGulagSkin(player);
        }
        plugin.saveConfig();
    }

    private int remaining(String path, String objective) {
        return Math.max(0, plugin.getConfig().getInt(path + "." + objective, 0)
                - plugin.getConfig().getInt(path + "." + objective + "-done", 0));
    }

    private boolean hasCompletedSentence(String path) {
        boolean hasAssignedObjective = false;
        for (String objective : List.of("coal", "wood", "deepslate")) {
            int required = plugin.getConfig().getInt(path + "." + objective, 0);
            if (required <= 0) {
                continue;
            }
            hasAssignedObjective = true;
            if (plugin.getConfig().getInt(path + "." + objective + "-done", 0) < required) {
                return false;
            }
        }
        return hasAssignedObjective;
    }

    private Zone findZone(Location location) {
        if (location.getWorld() == null) {
            return null;
        }
        for (Zone zone : loadZones()) {
            if (zone.world().equals(location.getWorld().getName())
                    && location.getBlockX() >= zone.minX() && location.getBlockX() <= zone.maxX()
                    && location.getBlockY() >= zone.minY() && location.getBlockY() <= zone.maxY()
                    && location.getBlockZ() >= zone.minZ() && location.getBlockZ() <= zone.maxZ()) {
                return zone;
            }
        }
        return null;
    }

    private List<Zone> loadZones() {
        List<Zone> zones = new ArrayList<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(ROOT + ".zones");
        if (section == null) {
            return zones;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection zoneSection = section.getConfigurationSection(key);
            if (zoneSection != null) {
                zones.add(readZone(key, zoneSection));
            }
        }
        return zones;
    }

    private Zone readZone(String key, ConfigurationSection section) {
        int x1 = section.getInt("min-x");
        int y1 = section.getInt("min-y");
        int z1 = section.getInt("min-z");
        int x2 = section.getInt("max-x");
        int y2 = section.getInt("max-y");
        int z2 = section.getInt("max-z");
        return new Zone(key, section.getString("name", key), section.getString("world", ""),
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2),
                configuredBlocks(section, "spawn-blocks"), configuredBlocks(section, "mine-blocks"),
                section.getInt("regen-percent", 70), section.getInt("eligible-blocks", 0));
    }

    private Zone createZone(String key, String name, String world, int x1, int y1, int z1,
                            int x2, int y2, int z2, Set<String> spawnBlocks, Set<String> mineBlocks,
                            int regenPercent, int eligibleBlocks) {
        return new Zone(key, name, world, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2),
                new LinkedHashSet<>(spawnBlocks), new LinkedHashSet<>(mineBlocks),
                regenPercent, eligibleBlocks);
    }

    private int initializeZoneBlocks(World world, Zone zone) {
        int count = 0;
        List<String> spawnTypes = new ArrayList<>(zone.spawnBlocks());
        for (int x = zone.minX(); x <= zone.maxX(); x++) {
            for (int y = zone.minY(); y <= zone.maxY(); y++) {
                for (int z = zone.minZ(); z <= zone.maxZ(); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    String current = block.getType().name();
                    if (ZONE_BLOCKS.contains(current)) {
                        String spawnName = spawnTypes.get(ThreadLocalRandom.current().nextInt(spawnTypes.size()));
                        Material spawnMaterial = Material.matchMaterial(spawnName);
                        if (spawnMaterial != null) {
                            block.setType(spawnMaterial, false);
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    private boolean overlaps(Zone first, Zone second) {
        return first.minX() <= second.maxX() && first.maxX() >= second.minX()
                && first.minY() <= second.maxY() && first.maxY() >= second.minY()
                && first.minZ() <= second.maxZ() && first.maxZ() >= second.minZ();
    }

    private boolean isCoalOre(String material) {
        return material.equals("COAL_ORE") || material.equals("DEEPSLATE_COAL_ORE");
    }

    private boolean isWoodLog(String material) {
        return material.endsWith("_LOG") || material.endsWith("_STEM") || material.endsWith("_HYPHAE");
    }

    private String objectiveForMaterial(String material) {
        if (material.equals("DEEPSLATE")) {
            return "deepslate";
        }
        if (isCoalOre(material)) {
            return "coal";
        }
        if (isWoodLog(material)) {
            return "wood";
        }
        return null;
    }

    private Set<String> configuredBlocks(ConfigurationSection section, String setting) {
        List<String> configured = section.getStringList(setting);
        if (configured.isEmpty()) {
            return defaultZoneBlocks(section.getBoolean("coal", true), section.getBoolean("wood", true));
        }
        Set<String> valid = new LinkedHashSet<>();
        for (String material : configured) {
            String normalized = material.toUpperCase(Locale.ROOT);
            if (ZONE_BLOCKS.contains(normalized) && Material.matchMaterial(normalized) != null) {
                valid.add(normalized);
            }
        }
        return valid;
    }

    private Set<String> defaultZoneBlocks(boolean coal, boolean wood) {
        Set<String> blocks = new LinkedHashSet<>();
        for (String material : availableZoneBlocks()) {
            if ((coal && isCoalOre(material)) || (wood && isWoodLog(material))) {
                blocks.add(material);
            }
        }
        return blocks;
    }

    private List<String> availableZoneBlocks() {
        return ZONE_BLOCKS.stream().filter(name -> {
            Material material = Material.matchMaterial(name);
            return material != null && material.isBlock();
        }).toList();
    }

    private String zoneKey(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}0-9_-]", "_");
    }

    private ItemStack item(String materialName, String displayName, String loreLine) {
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            material = Material.matchMaterial("STONE");
        }
        if (material == null) {
            return null;
        }
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(displayName);
            meta.setLore(List.of(loreLine));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private enum GuiType {
        PUNISHMENT,
        ZONE
    }

    private enum BlockPickerMode {
        SPAWN,
        MINE
    }

    private record Selection(String key, String name, String world, int x, int y, int z) {
    }

    private record Zone(String key, String name, String world, int minX, int minY, int minZ,
                        int maxX, int maxY, int maxZ, Set<String> spawnBlocks, Set<String> mineBlocks,
                        int regenPercent, int eligibleBlocks) {
    }

    private record BlockSnapshot(Location location, String material) {
    }

    private final class GuiHolder implements InventoryHolder {
        private final GuiType type;
        private final UUID playerId;
        private final String playerName;
        private final String zoneKey;
        private final String zoneName;
        private final Set<String> spawnBlocks;
        private final Set<String> mineBlocks;
        private final int regenPercent;
        private final boolean existing;
        private final BlockPickerMode pickerMode;
        private int coalAmount;
        private int woodAmount;
        private int deepslateAmount;
        private Inventory inventory;

        private GuiHolder(GuiType type, UUID playerId, String playerName, String zoneKey,
                          String zoneName, Set<String> spawnBlocks, Set<String> mineBlocks,
                          int regenPercent, boolean existing, BlockPickerMode pickerMode) {
            this.type = type;
            this.playerId = playerId;
            this.playerName = playerName;
            this.zoneKey = zoneKey;
            this.zoneName = zoneName;
            this.spawnBlocks = new LinkedHashSet<>(spawnBlocks);
            this.mineBlocks = new LinkedHashSet<>(mineBlocks);
            this.regenPercent = regenPercent;
            this.existing = existing;
            this.pickerMode = pickerMode;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}