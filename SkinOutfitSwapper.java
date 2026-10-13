package com.kodari.skinoutfitswapper;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class SkinOutfitSwapper extends JavaPlugin implements Listener, CommandExecutor {
    private SkinService skinService;
    private GulagasManager gulagasManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        skinService = new SkinService(this);
        gulagasManager = new GulagasManager(this);
        Objects.requireNonNull(getCommand("skinoutfit")).setExecutor(this);
        Objects.requireNonNull(getCommand("gulagas")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(gulagasManager, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("gulagas")) {
            return gulagasManager.handleCommand(sender, args);
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cŠią komandą gali naudoti tik žaidėjas.");
            return true;
        }
        if (getConfig().contains("gulagas.prisoners." + player.getUniqueId())) {
            player.sendMessage("§cGulago bausmės metu skino keisti negalima.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("apply")) {
            skinService.applyOutfit(player);
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reset")) {
            skinService.resetSkin(player);
            return true;
        }

        player.sendMessage("§eNaudojimas: /skinoutfit [apply|reset]");
        return true;
    }

    void applyGulagOutfit(Player player) {
        skinService.applyGulagOutfit(player);
    }

    void restoreGulagSkin(Player player) {
        skinService.restoreGulagSkin(player);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        getServer().getScheduler().runTaskLater(this,
                () -> skinService.reapplyActiveSkin(event.getPlayer()), 20L);
    }
}