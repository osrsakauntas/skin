package com.kodari.skinoutfitswapper;

import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerTextures;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

final class SkinService {
    private final SkinOutfitSwapper plugin;
    private final ConcurrentHashMap<UUID, AtomicBoolean> pendingPlayers = new ConcurrentHashMap<>();

    SkinService(SkinOutfitSwapper plugin) {
        this.plugin = plugin;
    }

    void applyOutfit(Player player) {
        UUID playerId = player.getUniqueId();
        AtomicBoolean pending = pendingPlayers.computeIfAbsent(playerId, ignored -> new AtomicBoolean());
        if (!pending.compareAndSet(false, true)) {
            player.sendMessage("§eTavo skinas jau apdorojamas.");
            return;
        }

        File outfitFile = new File(plugin.getDataFolder(), plugin.getConfig().getString("outfit-image", "outfit.png"));
        if (!outfitFile.isFile()) {
            File[] pngFiles = plugin.getDataFolder().listFiles((directory, name) -> name.toLowerCase(Locale.ROOT).endsWith(".png"));
            if (pngFiles != null && pngFiles.length == 1) {
                outfitFile = pngFiles[0];
            } else {
                pending.set(false);
                player.sendMessage("§cNerastas aprangos PNG. Įdėk PNG į plugins/SkinOutfitSwapper/ arba nurodyk jo pavadinimą config.yml faile (outfit-image).");
                return;
            }
        }

        String id = playerId.toString();
        String savedOriginal = plugin.getConfig().getString("saved-skins." + id);
        String sourceSkin = savedOriginal;
        String sourceModel = plugin.getConfig().getString("saved-models." + id);
        if (sourceSkin == null) {
            PlayerTextures currentTextures = player.getPlayerProfile().getTextures();
            URL currentSkin = currentTextures.getSkin();
            if (currentSkin == null) {
                pending.set(false);
                player.sendMessage("§cNepavyko rasti tavo originalaus skino tekstūros.");
                return;
            }
            sourceSkin = currentSkin.toExternalForm();
            PlayerTextures.SkinModel currentModel = currentTextures.getSkinModel();
            sourceModel = currentModel == null ? "CLASSIC" : currentModel.name();
        }

        final String originalSkinUrl = sourceSkin;
        final String originalModel = sourceModel == null ? "CLASSIC" : sourceModel;
        String endpoint = plugin.getConfig().getString("mineskin.endpoint", "https://api.mineskin.org/generate/upload");
        String apiKey = plugin.getConfig().getString("mineskin.api-key", "");
        File outfitImage = outfitFile;
        player.sendMessage("§7Kuriamas tavo skino variantas...");

        CompletableFuture.supplyAsync(() -> {
            try {
                byte[] composite = SkinImageMerger.merge(new URL(originalSkinUrl), outfitImage);
                return MineSkinClient.upload(composite, endpoint, apiKey);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((generated, failure) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            pending.set(false);
            pendingPlayers.remove(playerId, pending);
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause() : failure;
                plugin.getLogger().log(Level.WARNING, "Nepavyko sugeneruoti skino žaidėjui " + player.getName(), cause);
                if (player.isOnline()) {
                    String failureMessage = cause.getMessage();
                    if (failureMessage == null || failureMessage.isBlank()) {
                        failureMessage = cause.getClass().getSimpleName();
                    }
                    failureMessage = failureMessage.replaceAll("\\s+", " ").trim();
                    if (failureMessage.length() > 180) {
                        failureMessage = failureMessage.substring(0, 177) + "...";
                    }
                    player.sendMessage("§cSkino pakeisti nepavyko: §7" + failureMessage);
                }
                return;
            }

            plugin.getConfig().set("saved-skins." + id, originalSkinUrl);
            plugin.getConfig().set("saved-models." + id, originalModel);
            plugin.getConfig().set("active-skins." + id, generated.skinUrl());
            plugin.getConfig().set("active-models." + id, generated.model());
            plugin.getConfig().set("active-texture-values." + id, generated.textureValue());
            plugin.getConfig().set("active-texture-signatures." + id, generated.signature());
            plugin.saveConfig();
            if (player.isOnline()) {
                setSignedSkin(player, generated.textureValue(), generated.signature());
                player.sendMessage("§aApranga pakeista, o veidas ir plaukai išsaugoti. Jei pakeitimo nematai, atsijunk ir prisijunk.");
            }
        }));
    }

    void applyGulagOutfit(Player player) {
        UUID playerId = player.getUniqueId();
        String id = playerId.toString();
        String restorePath = "gulagas.skin-restores." + id;
        String currentTextureValue = plugin.getConfig().getString("active-texture-values." + id);
        String currentSignature = plugin.getConfig().getString("active-texture-signatures." + id);
        String currentSkin = null;
        String currentModel = "CLASSIC";
        PlayerTextures textures = player.getPlayerProfile().getTextures();
        URL skin = textures.getSkin();
        if (skin != null) {
            currentSkin = skin.toExternalForm();
            PlayerTextures.SkinModel model = textures.getSkinModel();
            currentModel = model == null ? "CLASSIC" : model.name();
        }
        ProfileProperty liveTexture = findTextureProperty(player);
        if (liveTexture != null) {
            currentTextureValue = liveTexture.getValue();
            currentSignature = liveTexture.getSignature();
        }
        if (currentSkin == null) {
            currentSkin = plugin.getConfig().getString("active-skins." + id);
            currentModel = plugin.getConfig().getString("active-models." + id, "CLASSIC");
        }
        if (currentTextureValue == null) {
            currentTextureValue = plugin.getConfig().getString("active-texture-values." + id);
            currentSignature = plugin.getConfig().getString("active-texture-signatures." + id);
        }
        if (currentSkin == null && currentTextureValue == null) {
            player.sendMessage("§cNepavyko rasti tavo originalaus skino tekstūros.");
            return;
        }
        if (!plugin.getConfig().contains(restorePath + ".skin")) {
            plugin.getConfig().set(restorePath + ".skin", currentSkin);
            plugin.getConfig().set(restorePath + ".model", currentModel);
            plugin.getConfig().set(restorePath + ".texture-value", currentTextureValue);
            plugin.getConfig().set(restorePath + ".texture-signature", currentSignature);
            plugin.saveConfig();
        } else {
            currentSkin = plugin.getConfig().getString(restorePath + ".skin", currentSkin);
            currentModel = plugin.getConfig().getString(restorePath + ".model", currentModel);
        }

        AtomicBoolean pending = pendingPlayers.computeIfAbsent(playerId, ignored -> new AtomicBoolean());
        if (!pending.compareAndSet(false, true)) {
            player.sendMessage("§eTavo gulago apranga jau apdorojama.");
            return;
        }

        File outfitFile = findOutfitFile(player, pending);
        if (outfitFile == null) {
            return;
        }
        final String sourceSkin = currentSkin;
        final String sourceModel = currentModel;
        String endpoint = plugin.getConfig().getString("mineskin.endpoint", "https://api.mineskin.org/generate/upload");
        String apiKey = plugin.getConfig().getString("mineskin.api-key", "");
        player.sendMessage("§7Paruošiama gulago apranga...");

        CompletableFuture.supplyAsync(() -> {
            try {
                byte[] composite = SkinImageMerger.merge(new URL(sourceSkin), outfitFile);
                return MineSkinClient.upload(composite, endpoint, apiKey);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((generated, failure) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            pending.set(false);
            pendingPlayers.remove(playerId, pending);
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause() : failure;
                plugin.getLogger().log(Level.WARNING, "Nepavyko sugeneruoti gulago aprangos žaidėjui " + player.getName(), cause);
                if (player.isOnline()) {
                    player.sendMessage("§cGulago aprangos pritaikyti nepavyko: §7" + cause.getMessage());
                }
                return;
            }

            if (!plugin.getConfig().contains("gulagas.prisoners." + id)) {
                return;
            }
            plugin.getConfig().set("gulagas.active-texture-values." + id, generated.textureValue());
            plugin.getConfig().set("gulagas.active-texture-signatures." + id, generated.signature());
            plugin.getConfig().set("gulagas.active-skins." + id, generated.skinUrl());
            plugin.getConfig().set("gulagas.active-models." + id, generated.model());
            plugin.saveConfig();
            if (player.isOnline()) {
                setSignedSkin(player, generated.textureValue(), generated.signature());
                player.sendMessage("§aGulago apranga pritaikyta iki bausmės pabaigos.");
            }
        }));
    }

    void restoreGulagSkin(Player player) {
        String id = player.getUniqueId().toString();
        String restorePath = "gulagas.skin-restores." + id;
        String skin = plugin.getConfig().getString(restorePath + ".skin");
        String model = plugin.getConfig().getString(restorePath + ".model", "CLASSIC");
        String textureValue = plugin.getConfig().getString(restorePath + ".texture-value");
        String signature = plugin.getConfig().getString(restorePath + ".texture-signature");
        plugin.getConfig().set(restorePath, null);
        plugin.getConfig().set("gulagas.active-texture-values." + id, null);
        plugin.getConfig().set("gulagas.active-texture-signatures." + id, null);
        plugin.getConfig().set("gulagas.active-skins." + id, null);
        plugin.getConfig().set("gulagas.active-models." + id, null);
        plugin.saveConfig();
        if (textureValue != null) {
            setTextureProperty(player, textureValue, signature);
        } else if (skin != null) {
            setSkin(player, skin, model);
        }
        player.sendMessage("§aGulago apranga nuimta, grąžintas ankstesnis skinas.");
    }

    void resetSkin(Player player) {
        String id = player.getUniqueId().toString();
        String originalSkin = plugin.getConfig().getString("saved-skins." + id);
        if (originalSkin == null) {
            player.sendMessage("§eNėra išsaugoto originalaus skino.");
            return;
        }

        String originalModel = plugin.getConfig().getString("saved-models." + id, "CLASSIC");
        plugin.getConfig().set("saved-skins." + id, null);
        plugin.getConfig().set("saved-models." + id, null);
        plugin.getConfig().set("active-skins." + id, null);
        plugin.getConfig().set("active-models." + id, null);
        plugin.getConfig().set("active-texture-values." + id, null);
        plugin.getConfig().set("active-texture-signatures." + id, null);
        plugin.saveConfig();
        setSkin(player, originalSkin, originalModel);
        player.sendMessage("§aSugrąžintas originalus tavo skinas.");
    }

    void reapplyActiveSkin(Player player) {
        String id = player.getUniqueId().toString();
        if (!plugin.getConfig().contains("gulagas.prisoners." + id)
                && plugin.getConfig().contains("gulagas.skin-restores." + id)) {
            restoreGulagSkin(player);
            return;
        }
        if (plugin.getConfig().contains("gulagas.prisoners." + id)) {
            String gulagTextureValue = plugin.getConfig().getString("gulagas.active-texture-values." + id);
            String gulagSignature = plugin.getConfig().getString("gulagas.active-texture-signatures." + id);
            if (gulagTextureValue != null && gulagSignature != null) {
                setSignedSkin(player, gulagTextureValue, gulagSignature);
            } else {
                applyGulagOutfit(player);
            }
            return;
        }
        String textureValue = plugin.getConfig().getString("active-texture-values." + id);
        String signature = plugin.getConfig().getString("active-texture-signatures." + id);
        if (textureValue != null && signature != null) {
            setSignedSkin(player, textureValue, signature);
            return;
        }

        String activeSkin = plugin.getConfig().getString("active-skins." + id);
        if (activeSkin == null) {
            return;
        }

        setSkin(player, activeSkin, plugin.getConfig().getString("active-models." + id, "CLASSIC"));
    }

    private File findOutfitFile(Player player, AtomicBoolean pending) {
        File outfitFile = new File(plugin.getDataFolder(), plugin.getConfig().getString("outfit-image", "outfit.png"));
        if (outfitFile.isFile()) {
            return outfitFile;
        }
        File[] pngFiles = plugin.getDataFolder().listFiles((directory, name) -> name.toLowerCase(Locale.ROOT).endsWith(".png"));
        if (pngFiles != null && pngFiles.length == 1) {
            return pngFiles[0];
        }
        pending.set(false);
        pendingPlayers.remove(player.getUniqueId(), pending);
        player.sendMessage("§cNerastas aprangos PNG. Įdėk PNG į plugins/SkinOutfitSwapper/ arba nurodyk jo pavadinimą config.yml faile (outfit-image).");
        return null;
    }

    private void setSignedSkin(Player player, String textureValue, String signature) {
        setTextureProperty(player, textureValue, signature);
    }

    private void setTextureProperty(Player player, String textureValue, String signature) {
        com.destroystokyo.paper.profile.PlayerProfile profile = Bukkit.createProfile(player.getUniqueId(), player.getName());
        ProfileProperty texture = signature == null || signature.isBlank()
                ? new ProfileProperty("textures", textureValue)
                : new ProfileProperty("textures", textureValue, signature);
        profile.setProperty(texture);
        player.setPlayerProfile(profile);
    }

    private ProfileProperty findTextureProperty(Player player) {
        com.destroystokyo.paper.profile.PlayerProfile profile = player.getPlayerProfile();
        for (ProfileProperty property : profile.getProperties()) {
            if (property.getName().equals("textures")) {
                return property;
            }
        }
        return null;
    }

    private void setSkin(Player player, String skinUrl, String modelName) {
        try {
            URL url = new URL(skinUrl);
            PlayerTextures.SkinModel model;
            try {
                model = PlayerTextures.SkinModel.valueOf(modelName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException | NullPointerException ignored) {
                model = PlayerTextures.SkinModel.CLASSIC;
            }
            String modelMetadata = model == PlayerTextures.SkinModel.SLIM
                    ? ",\"metadata\":{\"model\":\"slim\"}" : "";
            String textureJson = "{\"textures\":{\"SKIN\":{\"url\":\"" + url.toExternalForm()
                    + "\"" + modelMetadata + "}}}";
            String encodedTexture = Base64.getEncoder().encodeToString(textureJson.getBytes(StandardCharsets.UTF_8));
            com.destroystokyo.paper.profile.PlayerProfile profile = Bukkit.createProfile(player.getUniqueId(), player.getName());
            profile.setProperty(new ProfileProperty("textures", encodedTexture));
            player.setPlayerProfile(profile);
        } catch (MalformedURLException exception) {
            plugin.getLogger().log(Level.WARNING, "Neteisingas išsaugoto skino URL žaidėjui " + player.getName(), exception);
            player.sendMessage("§cIšsaugota skino nuoroda neteisinga.");
        }
    }
}