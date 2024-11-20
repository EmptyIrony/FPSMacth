package com.phasetranscrystal.fpsmatch.core;

import com.phasetranscrystal.fpsmatch.core.data.PlayerData;
import com.phasetranscrystal.fpsmatch.core.data.SpawnPointData;
import com.phasetranscrystal.fpsmatch.core.data.TabData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.scores.PlayerTeam;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

public class BaseTeam {
    private final String name;
    private final int playerLimit;
    private final PlayerTeam playerTeam;
    private int scores = 0;
    private final Map<UUID, PlayerData> players = new HashMap<>();
    private final List<SpawnPointData> spawnPointsData = new ArrayList<>();
    private int loseStreak;
    private int compensationFactor;

    public BaseTeam(String name, int playerLimit, PlayerTeam playerTeam) {
        this.name = name;
        this.playerLimit = playerLimit;
        this.playerTeam = playerTeam;
    }

    public void join(ServerPlayer player){
        player.getScoreboard().addPlayerToTeam(player.getScoreboardName(), playerTeam);
        this.createPlayerData(player);
    }

    public void leave(ServerPlayer player){
        if(this.hasPlayer(player.getUUID())){
            this.players.remove(player.getUUID());
            PlayerTeam currentTeam = player.getScoreboard().getPlayersTeam(player.getScoreboardName());
            if (currentTeam != null)  player.getScoreboard().removePlayerFromTeam(player.getScoreboardName(), currentTeam);
        }
    }

    public void createPlayerData(ServerPlayer player){
        this.players.put(player.getUUID(),new PlayerData(player.getUUID()));
    }

    public void handleOffline(ServerPlayer player){
        UUID uuid = player.getUUID();
        players.get(uuid).setLiving(false);
        players.get(uuid).setOffline(true);
    }
    public void resetLiving(){
        this.players.values().forEach((data)->{
            if(!data.isOffline()){
                data.setLiving(true);
            }
        });
    }

    @Nullable
    public TabData getPlayerTabData(UUID uuid){
        if(this.players.containsKey(uuid)){
            return this.players.get(uuid).getTabData();
        }
        return null;
    }

    @Nullable
    public PlayerData getPlayerData(UUID uuid){
        if(this.players.containsKey(uuid)){
            return this.players.get(uuid);
        }
        return null;
    }

    public List<PlayerData> getPlayersData(){
        return this.players.values().stream().toList();
    }

    public List<TabData> getPlayersTabData(){
        List<TabData> tabDataList = new ArrayList<>();
        this.players.values().forEach((data)->{
            tabDataList.add(data.getTabData());
        });
        return tabDataList;
    }

    public List<TabData> getPlayersTabDataTemp(){
        List<TabData> tabDataList = new ArrayList<>();
        this.players.values().forEach((data)->{
            tabDataList.add(data.getTabDataTemp());
        });
        return tabDataList;
    }

    public List<UUID> getPlayers(){
        return this.players.keySet().stream().toList();
    }

    public List<UUID> getOfflinePlayers() {
        List<UUID> offlinePlayers = new ArrayList<>();
        this.players.values().forEach((data)->{
            if(data.isOffline()){
                offlinePlayers.add(data.getOwner());
            }
        });
        return offlinePlayers;
    }

    public List<UUID> getLivingPlayers(){
        List<UUID> uuids = new ArrayList<>();
        this.players.values().forEach((data)->{
            if(data.getTabData().isLiving()){
                uuids.add(data.getOwner());
            }
        });
        return uuids;
    }


    public boolean hasPlayer(UUID uuid){
        return this.players.containsKey(uuid);
    }

    public void randomSpawnPoints() {
        Random random = new Random();

        if (this.spawnPointsData.isEmpty()) {
            throw new RuntimeException(new IllegalStateException("No spawn points available."));
        }

        if (this.spawnPointsData.size() < this.players.size()) {
            throw new RuntimeException(new IllegalStateException("Not enough spawn points for all players."));
        }

        List<UUID> playerUUIDs = new ArrayList<>(this.players.keySet());

        for (UUID playerUUID : playerUUIDs) {
            int randomIndex = random.nextInt(this.spawnPointsData.size());
            SpawnPointData spawnPoint = this.spawnPointsData.get(randomIndex);
            this.players.get(playerUUID).setSpawnPointsData(spawnPoint);
        }
    }

    public void addSpawnPointData(@Nonnull SpawnPointData data){
        this.spawnPointsData.add(data);
    }

    public void addAllSpawnPointData(@Nonnull List<SpawnPointData> data){
        this.spawnPointsData.addAll(data);
    }

    public void resetSpawnPointData(){
        this.spawnPointsData.clear();
    }

    public List<SpawnPointData> getSpawnPointsData(){
        return spawnPointsData;
    }
    public int getPlayerLimit() {
        return playerLimit;
    }

    public int getRemainingLimit(){
        return playerLimit - this.players.size();
    }

    public PlayerTeam getPlayerTeam() {
        return playerTeam;
    }

    public int getScores() {
        return scores;
    }

    public void setScores(int scores) {
        this.scores = scores;
    }

    public String getName() {
        return name;
    }

    // 获取连败次数
    public int getLoseStreak() {
        return loseStreak;
    }

    // 设置连败次数
    public void setLoseStreak(int loseStreak) {
        this.loseStreak = loseStreak;
    }

    // 获取战败补偿因数
    public int getCompensationFactor() {
        return compensationFactor;
    }

    // 设置战败补偿因数
    public void setCompensationFactor(int compensationFactor) {
        this.compensationFactor = Math.max(0, Math.min(compensationFactor, 4));
    }

}
