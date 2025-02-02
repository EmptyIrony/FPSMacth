package com.phasetranscrystal.fpsmatch.cs;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.phasetranscrystal.fpsmatch.FPSMatch;
import com.phasetranscrystal.fpsmatch.core.*;
import com.phasetranscrystal.fpsmatch.core.codec.FPSMCodec;
import com.phasetranscrystal.fpsmatch.core.data.*;
import com.phasetranscrystal.fpsmatch.core.data.save.ISavedData;
import com.phasetranscrystal.fpsmatch.core.event.PlayerKillOnMapEvent;
import com.phasetranscrystal.fpsmatch.core.map.BaseMap;
import com.phasetranscrystal.fpsmatch.core.map.BlastModeMap;
import com.phasetranscrystal.fpsmatch.core.map.GiveStartKitsMap;
import com.phasetranscrystal.fpsmatch.core.map.ShopMap;
import com.phasetranscrystal.fpsmatch.core.shop.ItemType;
import com.phasetranscrystal.fpsmatch.core.shop.ShopData;
import com.phasetranscrystal.fpsmatch.core.shop.slot.ShopSlot;
import com.phasetranscrystal.fpsmatch.entity.CompositionC4Entity;
import com.phasetranscrystal.fpsmatch.entity.MatchDropEntity;
import com.phasetranscrystal.fpsmatch.item.BombDisposalKit;
import com.phasetranscrystal.fpsmatch.item.CompositionC4;
import com.phasetranscrystal.fpsmatch.item.FPSMItemRegister;
import com.phasetranscrystal.fpsmatch.net.*;
import com.phasetranscrystal.fpsmatch.net.FPSMatchGameTypeS2CPacket;
import com.phasetranscrystal.fpsmatch.util.FPSMUtil;
import com.tacz.guns.api.event.common.EntityKillByGunEvent;
import com.tacz.guns.api.item.IGun;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;


@Mod.EventBusSubscriber(modid = FPSMatch.MODID,bus = Mod.EventBusSubscriber.Bus.FORGE)
public class CSGameMap extends BaseMap implements BlastModeMap<CSGameMap> , ShopMap<CSGameMap> , GiveStartKitsMap<CSGameMap> , ISavedData<CSGameMap> {
    private int autoStartTime = 6000;
    private static final Map<String, BiConsumer<CSGameMap,ServerPlayer>> COMMANDS = registerCommands();
    private static final Map<String, Consumer<CSGameMap>> VOTE_ACTION = registerVoteAction();
    public int winnerRound = 13; // 13回合
    public int pauseTime = 1200; // 60秒
    public int winnerWaitingTime = 160;
    public int warmUpTime = 1200;
    private int waitingTime = 300;
    private int currentPauseTime = 0;
    private int roundTimeLimit = 2300;
    private int currentRoundTime = 0;
    private boolean isError = false;
    private boolean isPause = false;
    private boolean isWaiting = false;
    private boolean isWarmTime = false;
    private boolean isWaitingWinner = false;
    private boolean isShopLocked = false;
    private int isBlasting = 0; // 是否放置炸弹 0 = 未放置 | 1 = 已放置 | 2 = 已拆除
    private boolean isExploded = false; // 炸弹是否爆炸
    private final List<AreaData> bombAreaData = new ArrayList<>();
    private String blastTeam;
    private final Map<String,FPSMShop> shop = new HashMap<>();
    private int startMoney = 800;
    private final Map<String,List<ItemStack>> startKits = new HashMap<>();
    private boolean isOvertime = false;
    private int overCount = 0;
    private boolean isWaitingOverTimeVote = false;
    private VoteObj voteObj = null;
    private SpawnPointData matchEndTeleportPoint = null;
    private int autoStartTimer = 0;
    private boolean autoStartFirstMessageFlag = false;

    public static Map<String, BiConsumer<CSGameMap,ServerPlayer>> registerCommands(){
        Map<String, BiConsumer<CSGameMap,ServerPlayer>> commands = new HashMap<>();
        commands.put("p", CSGameMap::setPauseState);
        commands.put("pause", CSGameMap::setPauseState);
        commands.put("unpause", CSGameMap::startUnpauseVote);
        commands.put("up", CSGameMap::startUnpauseVote);
        commands.put("agree",CSGameMap::handleAgreeCommand);
        commands.put("a",CSGameMap::handleAgreeCommand);
        commands.put("disagree",CSGameMap::handleDisagreeCommand);
        commands.put("da",CSGameMap::handleDisagreeCommand);
        commands.put("start",CSGameMap::handleStartCommand);
        commands.put("reset",CSGameMap::handleResetCommand);
        commands.put("log",CSGameMap::handleLogCommand);
        return commands;
    }

    private void handleResetCommand(ServerPlayer serverPlayer) {
        if(this.voteObj == null && this.isStart){
            this.startVote("reset",Component.translatable("fpsm.map.vote.message",serverPlayer.getDisplayName(),Component.translatable("fpsm.cs.reset")),20,1f);
            this.voteObj.addAgree(serverPlayer);
        } else if (this.voteObj != null) {
            Component translation = Component.translatable("fpsm.cs." + this.voteObj.getVoteTitle());
            serverPlayer.displayClientMessage(Component.translatable("fpsm.map.vote.fail.alreadyHasVote", translation).withStyle(ChatFormatting.RED),false);
        }
    }

    private void handleLogCommand(ServerPlayer serverPlayer) {
        serverPlayer.displayClientMessage(Component.literal("-----------------INFO----------------").withStyle(ChatFormatting.GREEN), false);

        serverPlayer.displayClientMessage(Component.literal("| type ").withStyle(ChatFormatting.GRAY).append(
                Component.literal("[" + this.getGameType() + "]").withStyle(ChatFormatting.DARK_AQUA)), false);
        serverPlayer.displayClientMessage(Component.literal("| name ").withStyle(ChatFormatting.GRAY).append(
                Component.literal("[" + this.getMapName() + "]").withStyle(ChatFormatting.DARK_AQUA)), false);

        serverPlayer.displayClientMessage(Component.literal("| isStart ").withStyle(ChatFormatting.GRAY).append(
                formatBoolean(this.isStart)), false);
        serverPlayer.displayClientMessage(Component.literal("| isPause ").withStyle(ChatFormatting.GRAY).append(
                formatBoolean(this.isPause)), false);
        serverPlayer.displayClientMessage(Component.literal("| isWaiting ").withStyle(ChatFormatting.GRAY).append(
                formatBoolean(this.isWaiting)), false);
        serverPlayer.displayClientMessage(Component.literal("| isWaitingWinner ").withStyle(ChatFormatting.GRAY).append(
                formatBoolean(this.isWaitingWinner)), false);

        serverPlayer.displayClientMessage(Component.literal("| isBlasting ").withStyle(ChatFormatting.GRAY).append(
                Component.literal("[" + this.isBlasting + "]").withStyle(ChatFormatting.DARK_AQUA)), false);
        serverPlayer.displayClientMessage(Component.literal("| isExploded ").withStyle(ChatFormatting.GRAY).append(
                formatBoolean(this.isExploded)), false);
        serverPlayer.displayClientMessage(Component.literal("| isOvertime ").withStyle(ChatFormatting.GRAY).append(
                formatBoolean(this.isOvertime)), false);
        serverPlayer.displayClientMessage(Component.literal("| overCount ").withStyle(ChatFormatting.GRAY).append(
                Component.literal("[" + this.overCount + "]").withStyle(ChatFormatting.DARK_AQUA)), false);

        serverPlayer.displayClientMessage(Component.literal("| isWaitingOverTimeVote ").withStyle(ChatFormatting.GRAY).append(
                formatBoolean(this.isWaitingOverTimeVote)), false);
        serverPlayer.displayClientMessage(Component.literal("| currentPauseTime ").withStyle(ChatFormatting.GRAY).append(
                Component.literal("[" + this.currentPauseTime + "]").withStyle(ChatFormatting.DARK_AQUA)), false);
        serverPlayer.displayClientMessage(Component.literal("| autoStartTimer ").withStyle(ChatFormatting.GRAY).append(
                Component.literal("[" + this.autoStartTimer + "]").withStyle(ChatFormatting.DARK_AQUA)), false);

        serverPlayer.displayClientMessage(Component.literal("| autoStartFirstMessageFlag ").withStyle(ChatFormatting.GRAY).append(
                formatBoolean(this.autoStartFirstMessageFlag)), false);
        serverPlayer.displayClientMessage(Component.literal("| waitingTime ").withStyle(ChatFormatting.GRAY).append(
                Component.literal("[" + this.waitingTime + "]").withStyle(ChatFormatting.DARK_AQUA)), false);
        serverPlayer.displayClientMessage(Component.literal("| currentRoundTime ").withStyle(ChatFormatting.GRAY).append(
                Component.literal("[" + this.currentRoundTime + "]").withStyle(ChatFormatting.DARK_AQUA)), false);

        serverPlayer.displayClientMessage(Component.literal("| isShopLocked ").withStyle(ChatFormatting.GRAY).append(
                formatBoolean(this.isShopLocked)), false);
        serverPlayer.displayClientMessage(Component.literal("| isWarmTime ").withStyle(ChatFormatting.GRAY).append(
                formatBoolean(this.isWarmTime)), false);
        serverPlayer.displayClientMessage(Component.literal("| isError ").withStyle(ChatFormatting.GRAY).append(
                formatBoolean(this.isError)), false);

        for (BaseTeam team : this.getMapTeams().getTeams()) {
            serverPlayer.displayClientMessage(Component.literal("-----------------------------------").withStyle(ChatFormatting.GREEN), false);
            serverPlayer.displayClientMessage(Component.literal("info: team-").withStyle(ChatFormatting.GRAY).append(
                    Component.literal("[" + team.name + "]").withStyle(ChatFormatting.DARK_AQUA)).append(
                    Component.literal(" | player Count : ").withStyle(ChatFormatting.GRAY)).append(
                    Component.literal("[" + team.getPlayers().size() + "]").withStyle(ChatFormatting.DARK_AQUA)), false);
            for (PlayerData tabData : team.getPlayers().values()) {
                MutableComponent playerNameComponent = Component.literal("Player: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(this.getMapTeams().playerName.get(tabData.getOwner()).getString()).withStyle(ChatFormatting.DARK_GREEN));

                MutableComponent tabDataComponent = Component.literal(" | Tab Data: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal("[" + tabData.getTabData().getTabString() + "]").withStyle(ChatFormatting.DARK_AQUA));

                MutableComponent damagesComponent = Component.literal(" | damages : ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal("[" + tabData.getTabData().getDamage() + "]").withStyle(ChatFormatting.DARK_AQUA));

                MutableComponent isLivingComponent = Component.literal(" | isLiving :").withStyle(ChatFormatting.GRAY)
                        .append(formatBoolean(tabData.getTabData().isLiving()));

                serverPlayer.displayClientMessage(playerNameComponent.append(tabDataComponent).append(damagesComponent).append(isLivingComponent), false);
            }
            serverPlayer.displayClientMessage(Component.literal("-----------------------------------").withStyle(ChatFormatting.GREEN), false);
        }
    }

    private Component formatBoolean(boolean value){
        return Component.literal(String.valueOf(value)).withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private void handleStartCommand(ServerPlayer serverPlayer) {
        if((!this.isStart && this.voteObj == null) || (!this.isStart && !this.voteObj.getVoteTitle().equals("start"))){
            this.startVote("start",Component.translatable("fpsm.map.vote.message",serverPlayer.getDisplayName(),Component.translatable("fpsm.cs.start")),20,1f);
            this.voteObj.addAgree(serverPlayer);
        }
    }

    public static Map<String, Consumer<CSGameMap>> registerVoteAction(){
        Map<String, Consumer<CSGameMap>> commands = new HashMap<>();
        commands.put("overtime",CSGameMap::startOvertime);
        commands.put("unpause", CSGameMap::setUnPauseState);
        commands.put("reset", CSGameMap::resetGame);
        commands.put("start",CSGameMap::startGame);
        return commands;
    }

    public CSGameMap(ServerLevel serverLevel,String mapName,AreaData areaData) {
        super(serverLevel,mapName,areaData);
        this.addTeam("ct",5);
        this.addTeam("t",5);
        this.setBlastTeam("t");
    }
    @Override
    public void addTeam(String teamName,int playerLimit){
        super.addTeam(teamName,playerLimit);
        this.shop.put(teamName,new FPSMShop(this.getMapName(),this.startMoney));
    }

    public void startVote(String title,Component message,int second,float playerPercent){
        if(this.voteObj == null){
            this.voteObj = new VoteObj(title,message,second,playerPercent);
            this.sendAllPlayerMessage(message,false);
            this.sendAllPlayerMessage(Component.translatable("fpsm.map.vote.help").withStyle(ChatFormatting.GREEN),false);
        }
    }

    @Override
    public FPSMShop getShop(String shopName) {
        return shop.getOrDefault(shopName,null);
    }

    @Override
    public List<FPSMShop> getShops() {
        return this.shop.values().stream().toList();
    }

    @Override
    public List<String> getShopNames() {
        return this.shop.keySet().stream().toList();
    }

    @Override
    public void tick() {
        if(isStart && !checkPauseTime()){
            // 暂停 / 热身 / 回合开始前的等待时间
            if (!checkWarmUpTime() & !checkWaitingTime()) {
                if(!isRoundTimeEnd()){
                    if(!this.isDebug()){
                        boolean flag = this.getMapTeams().getJoinedPlayers().size() != 1;
                        switch (this.isBlasting()){
                            case 1 : this.checkBlastingVictory(); break;
                            case 2 : if(!isWaitingWinner) this.roundVictory(this.getCTTeam(),WinnerReason.DEFUSE_BOMB); break;
                            default : if(flag) this.checkRoundVictory(); break;
                        }

                        // 回合结束等待时间
                        if(this.isWaitingWinner){
                            checkWinnerTime();

                            if(this.currentPauseTime >= winnerWaitingTime){
                                this.startNewRound();
                            }
                        }
                    }
                }else{
                    if(!checkWinnerTime()){
                        this.roundVictory(this.getCTTeam(),WinnerReason.TIME_OUT);
                    }else if(this.currentPauseTime >= winnerWaitingTime){
                        this.startNewRound();
                    }
                }
            }
        }

        this.voteLogic();
        this.autoStartLogic();
    }


private void autoStartLogic(){
    if(isStart) {
        autoStartTimer = 0;
        autoStartFirstMessageFlag = false;
        return;
    }

    List<BaseTeam> teams = this.getMapTeams().getTeams();
    if(!teams.get(0).getPlayerList().isEmpty() && !teams.get(1).getPlayerList().isEmpty()){
        autoStartTimer++;
        if(!autoStartFirstMessageFlag){
            this.sendAllPlayerMessage(Component.translatable("fpsm.map.cs.auto.start.message", autoStartTime / 20).withStyle(ChatFormatting.YELLOW),false);
            autoStartFirstMessageFlag = true;
        }
    } else {
        autoStartTimer = 0;
    }

    if(this.autoStartTimer > 0){
        if ((autoStartTimer >= 600 && autoStartTimer % 200 == 0) || (autoStartTimer >= 1000 && autoStartTimer <= 1180 && autoStartTimer % 20 == 0)) {
            this.getMapTeams().getJoinedPlayers().forEach((uuid -> {
                ServerPlayer serverPlayer = this.getServerLevel().getServer().getPlayerList().getPlayer(uuid);
                if (serverPlayer != null) {
                    serverPlayer.connection.send(new ClientboundSetTitleTextPacket(Component.translatable("fpsm.map.cs.auto.start.title", (autoStartTime - autoStartTimer) / 20).withStyle(ChatFormatting.YELLOW)));
                    serverPlayer.connection.send(new ClientboundSetSubtitleTextPacket(Component.translatable("fpsm.map.cs.auto.start.subtitle").withStyle(ChatFormatting.YELLOW)));
                }
            }));
        } else {
            if(autoStartTimer % 20 == 0){
                if(this.voteObj == null) this.sendAllPlayerMessage(Component.translatable("fpsm.map.cs.auto.start.actionbar",(autoStartTime - autoStartTimer) / 20).withStyle(ChatFormatting.YELLOW),true);
            }

            if(autoStartTimer >= 1200){
                this.getMapTeams().getJoinedPlayers().forEach((uuid -> {
                    ServerPlayer serverPlayer = this.getServerLevel().getServer().getPlayerList().getPlayer(uuid);
                    if (serverPlayer != null) {
                        serverPlayer.connection.send(new ClientboundSetTitleTextPacket(Component.translatable("fpsm.map.cs.auto.started").withStyle(ChatFormatting.YELLOW)));
                        serverPlayer.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("")));
                    }
                }));
                this.autoStartTimer = 0;
                this.startGame();
            }
        }
    }
}

    public void joinTeam(ServerPlayer player) {
        FPSMCore.checkAndLeaveTeam(player);
        MapTeams mapTeams = this.getMapTeams();
        List<BaseTeam> baseTeams = mapTeams.getTeams();
        if(baseTeams.isEmpty()) return;
        BaseTeam team = baseTeams.stream().min(Comparator.comparingInt(BaseTeam::getPlayerCount)).orElse(baseTeams.stream().toList().get(new Random().nextInt(0,baseTeams.size())));
        this.joinTeam(team.name, player);
    }

    private void setBystander(ServerPlayer player) {
        List<UUID> uuids = this.getMapTeams().getSameTeamPlayerUUIDs(player);
        uuids.remove(player.getUUID());
        Entity entity = null;
        if (uuids.size() > 1) {
            Random random = new Random();
            entity = this.getServerLevel().getEntity(uuids.get(random.nextInt(0, uuids.size())));
        } else if (!uuids.isEmpty()) {
            entity = this.getServerLevel().getEntity(uuids.get(0));
        }
        if (entity != null) {
            player.setCamera(entity);
        }
    }

    @Override
    public void joinTeam(String teamName, ServerPlayer player) {
        MapTeams mapTeams = this.getMapTeams();
        mapTeams.joinTeam(teamName, player);
        
        // 同步游戏类型和地图信息
        this.sendPacketToJoinedPlayer(player,new FPSMatchGameTypeS2CPacket(this.getMapName(), this.getGameType()),true);

        // 同步新加入玩家的信息给所有人
        this.sendPacketToAllPlayer(new CSGameTabStatsS2CPacket(player.getUUID(),
                Objects.requireNonNull(Objects.requireNonNull(this.getMapTeams().getTeamByName(teamName))
                        .getPlayerData(player.getUUID())).getTabData(),
                teamName));
                
        // 同步所有已存在玩家的信息给新玩家
        for (BaseTeam team : mapTeams.getTeams()) {
            for (UUID existingPlayerId : team.getPlayers().keySet()) {
                if (!existingPlayerId.equals(player.getUUID())) {
                    var packet = new CSGameTabStatsS2CPacket(existingPlayerId,
                            Objects.requireNonNull(team.getPlayerData(existingPlayerId)).getTabData(),
                            team.name);
                    this.sendPacketToJoinedPlayer(player,packet,true);
                }
            }
        }

        // 同步商店数据
        this.getShop(teamName).syncShopData(player);

        // 如果游戏已经开始，设置玩家为旁观者
        if(this.isStart){
            player.setGameMode(GameType.SPECTATOR);
            BaseTeam team = mapTeams.getTeamByName(teamName);
            if(team != null){
               PlayerData data = team.getPlayerData(player.getUUID());
               if(data != null){
                   data.setLiving(false);
               }
            }

            setBystander(player);
        }
    }

    private void voteLogic() {
        if(this.voteObj != null){
            this.sendAllPlayerMessage(Component.translatable("fpsm.map.vote.timer",(this.voteObj.getEndVoteTimer() - System.currentTimeMillis()) / 1000).withStyle(ChatFormatting.DARK_AQUA),true);
            int joinedPlayer = this.getMapTeams().getJoinedPlayers().size();
            AtomicInteger count = new AtomicInteger();
            this.voteObj.voteResult.values().forEach(aBoolean -> {
                if (aBoolean){
                    count.addAndGet(1);
                }
            });
            boolean accept = (float) count.get() / joinedPlayer >= this.voteObj.getPlayerPercent();
            if(this.voteObj.checkVoteIsOverTime() || this.voteObj.voteResult.keySet().size() == joinedPlayer || accept){
                Component translation = Component.translatable("fpsm.cs." + this.voteObj.getVoteTitle());
                if(accept){
                    if(VOTE_ACTION.containsKey(this.voteObj.getVoteTitle())){
                        this.sendAllPlayerMessage(Component.translatable("fpsm.map.vote.success",translation).withStyle(ChatFormatting.GREEN),false);
                        VOTE_ACTION.get(this.voteObj.getVoteTitle()).accept(this);
                    }
                }else{
                    this.sendAllPlayerMessage(Component.translatable("fpsm.map.vote.fail",translation).withStyle(ChatFormatting.RED),false);
                    List<UUID> players = this.getMapTeams().getJoinedPlayers();
                    this.voteObj.voteResult.keySet().forEach(players::remove);
                    for (UUID uuid : players) {
                        Component name = this.getMapTeams().playerName.getOrDefault(uuid, Component.literal(uuid.toString()));
                        this.sendAllPlayerMessage(Component.translatable("fpsm.map.vote.disagree", name).withStyle(ChatFormatting.RED), false);
                    }

                    if(this.voteObj.getVoteTitle().equals("overtime")){
                        this.isPause = false;
                        this.currentPauseTime = 0;
                        this.syncToClient();
                        this.resetGame();
                    }
                }
                this.voteObj = null;
            }
        }
    }

    private void checkErrorPlayerTeam() {
      /*  this.getMapTeams().getTeams().forEach(team->{
            team.getPlayerList().forEach(uuid->{
                if(this.getServerLevel().getPlayerByUUID(uuid) == null){
                    team.delPlayer(uuid);
                    this.sendPacketToAllPlayer(new FPSMatchTabRemovalS2CPacket(uuid));
                };
            });
        });*/
    }

    public void startGame(){
        this.getMapTeams().setTeamNameColor(this,"ct",ChatFormatting.BLUE);
        this.getMapTeams().setTeamNameColor(this,"t",ChatFormatting.YELLOW);
        AtomicBoolean checkFlag = new AtomicBoolean(true);
        this.getMapTeams().getJoinedPlayers().forEach((uuid -> {
            ServerPlayer player = this.getServerLevel().getServer().getPlayerList().getPlayer(uuid);
            if (player != null){
                BaseTeam team = this.getMapTeams().getTeamByPlayer(player);
                if(team == null) checkFlag.set(false);
            }else{
                checkFlag.set(false);
            }
        }));

        if (!checkFlag.get() && !this.isError) return;
        this.getServerLevel().getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(true,null);
        this.getServerLevel().getGameRules().getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN).set(true,null);
        this.getServerLevel().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,null);
        this.getServerLevel().getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false,null);
        this.getServerLevel().getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,null);
        this.getServerLevel().getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(false,null);
        this.getServerLevel().getServer().setDifficulty(Difficulty.HARD,true);
        this.isOvertime = false;
        this.overCount = 0;
        this.isWaitingOverTimeVote = false;
        this.isStart = true;
        this.isWaiting = true;
        this.isWaitingWinner = false;
        this.setBlasting(0);
        this.setExploded(false);
        this.currentRoundTime = 0;
        this.currentPauseTime = 0;
        this.isShopLocked = false;
        this.getMapTeams().setTeamsSpawnPoints();
        this.getMapTeams().resetLivingPlayers();
        this.getMapTeams().getJoinedPlayers().forEach((uuid -> {
            ServerPlayer serverPlayer = this.getServerLevel().getServer().getPlayerList().getPlayer(uuid);
            if(serverPlayer != null){
                syncNormalRoundStartMessage(serverPlayer);
                serverPlayer.removeAllEffects();
                serverPlayer.addEffect(new MobEffectInstance(MobEffects.SATURATION,-1,2,false,false,false));
                serverPlayer.heal(serverPlayer.getMaxHealth());
                serverPlayer.setGameMode(GameType.ADVENTURE);
                this.clearPlayerInventory(serverPlayer);
                this.teleportPlayerToReSpawnPoint(serverPlayer);
            }
        }));
        this.giveAllPlayersKits();
        this.giveBlastTeamBomb();
        this.syncShopData();
        this.getMapTeams().getJoinedPlayers().forEach((uuid -> this.setPlayerMoney(uuid,800)));
    }

    public boolean canRestTime(){
        return !this.isPause && !this.isWarmTime && !this.isWaiting && !this.isWaitingWinner;
    }

    public boolean checkPauseTime(){
        if(this.isPause && currentPauseTime < pauseTime){
            this.currentPauseTime++;
        }else{
            if(this.isPause) {
                currentPauseTime = 0;
                if(this.voteObj != null && this.voteObj.getVoteTitle().equals("unpause")){
                    this.voteObj = null;
                }
                this.sendAllPlayerMessage(Component.translatable("fpsm.map.cs.pause.done").withStyle(ChatFormatting.GOLD),false);
            }
            isPause = false;
        }
        return this.isPause;
    }

    public boolean checkWarmUpTime(){
        if(this.isWarmTime && currentPauseTime < warmUpTime){
            this.currentPauseTime++;
        }else {
            if(this.canRestTime()) {
                currentPauseTime = 0;
            }
            isWarmTime = false;
        }
        return this.isWarmTime;
    }

    public boolean checkWaitingTime(){
        if(this.isWaiting && currentPauseTime < waitingTime){
            this.currentPauseTime++;
            boolean b = false;
            Iterator<BaseTeam> teams = this.getMapTeams().getTeams().iterator();
            while (teams.hasNext()){
                BaseTeam baseTeam = teams.next();
                if(!b){
                    b = baseTeam.needPause();
                    if(b){
                        baseTeam.setNeedPause(false);
                    }
                }else{
                    baseTeam.resetPauseIfNeed();
                }
                teams.remove();
            }

            if(b){
                this.sendAllPlayerMessage(Component.translatable("fpsm.map.cs.pause.now").withStyle(ChatFormatting.GOLD),false);
                this.isPause = true;
                this.currentPauseTime = 0;
                this.isWaiting = true;
            }
        }else {
            if(this.canRestTime()) currentPauseTime = 0;
            isWaiting = false;
        }
        return this.isWaiting;
    }

    public boolean checkWinnerTime(){
        if(this.isWaitingWinner && currentPauseTime < winnerWaitingTime){
            this.currentPauseTime++;
        }else{
            if(this.canRestTime()) currentPauseTime = 0;
        }
        return this.isWaitingWinner;
    }

    public void checkRoundVictory(){
        if(isWaitingWinner) return;
        Map<String, List<UUID>> teamsLiving = this.getMapTeams().getTeamsLiving();
        if(teamsLiving.size() == 1){
            String winnerTeam = teamsLiving.keySet().stream().findFirst().get();
            BaseTeam check = this.getMapTeams().getTeamByName(winnerTeam);
            if (check != null) {
                this.roundVictory(check,WinnerReason.ACED);
            }else{
                FPSMatch.LOGGER.error("Winner team is null: " + winnerTeam);
            }
        }

        if(teamsLiving.isEmpty()){
            this.roundVictory(this.getCTTeam(),WinnerReason.ACED);
        }
    }

    public void checkBlastingVictory(){
        if(isWaitingWinner) return;
        if(this.isExploded()) {
            this.roundVictory(this.getTTeam(),WinnerReason.DETONATE_BOMB);
        }else {
            Map<String, List<UUID>> teamsLiving = this.getMapTeams().getTeamsLiving();
            if(teamsLiving.size() == 1){
                String winnerTeam = teamsLiving.keySet().stream().findFirst().get();
                BaseTeam check = this.getMapTeams().getTeamByName(winnerTeam);
                boolean flag = this.checkCanPlacingBombs(Objects.requireNonNull(check).getFixedName());
                if(flag){
                    this.roundVictory(check,WinnerReason.ACED);
                }
            }else if(teamsLiving.isEmpty()){
                this.roundVictory(this.getTTeam(),WinnerReason.ACED);
            }
        }
    }

    public boolean isRoundTimeEnd(){
        if(this.isBlasting() > 0){
            this.currentRoundTime = -1;
            return false;
        }
        if(this.currentRoundTime < this.roundTimeLimit){
            this.currentRoundTime++;
        }
        if((this.currentRoundTime >= 200 || this.currentRoundTime == -1 ) && !this.isShopLocked){
            var packet = new ShopStatesS2CPacket(false);
            this.sendPacketToAllPlayer(packet);
            this.isShopLocked = true;
        }
        return this.currentRoundTime >= this.roundTimeLimit;
    }

    public void showWinnerMessage(String winnerTeamName){
        this.getMapTeams().getJoinedPlayers().forEach((uuid -> {
            ServerPlayer serverPlayer = (ServerPlayer) this.getServerLevel().getPlayerByUUID(uuid);
            if(serverPlayer != null){
                serverPlayer.connection.send(new ClientboundSetTitleTextPacket(Component.translatable("fpsm.map.cs.winner."+winnerTeamName+".round.message").withStyle(winnerTeamName.equals("ct") ? ChatFormatting.DARK_AQUA : ChatFormatting.YELLOW)));
            }
        }));
    }

    public void sendAllPlayerTitle(Component title,@Nullable Component subtitle){
        ServerLevel level = this.getServerLevel();
        this.getMapTeams().getJoinedPlayers().forEach((uuid -> {
            ServerPlayer serverPlayer = level.getServer().getPlayerList().getPlayer(uuid);
            if(serverPlayer != null){
                serverPlayer.connection.send(new ClientboundSetTitleTextPacket(title));
                if(subtitle != null){
                    serverPlayer.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
                }
            }
        }));
    }

    /**
     * 处理回合胜利的逻辑
     * 将isWaitingWinner设置成true以倒计时处理startNewRound逻辑
     * @param winnerTeam 胜利队伍
     * @param reason 胜利原因
     */
    private void roundVictory(@NotNull BaseTeam winnerTeam, @NotNull WinnerReason reason) {
        // 检查获胜队伍是否存在
            // 如果已经在等待胜利者，则直接返回
            if(isWaitingWinner) return;
            this.showWinnerMessage(winnerTeam.name);
            // 设置为等待胜利者状态
            this.isWaitingWinner = true;
        int currentScore = winnerTeam.getScores();
        int target = currentScore + 1;
        List<BaseTeam> baseTeams =this.getMapTeams().getTeams();
        if(target == 12 && baseTeams.remove(winnerTeam) && baseTeams.get(0).getScores() == 12 && !this.isOvertime){
            this.isWaitingOverTimeVote = true;
        }
        winnerTeam.setScores(target);

        // 获取胜利队伍和失败队伍列表
            List<BaseTeam> lostTeams = this.getMapTeams().getTeams();
            lostTeams.remove(winnerTeam);

            // 处理胜利经济奖励
            int reward = reason.winMoney;

        // 遍历所有玩家，更新经济
            this.getMapTeams().getJoinedPlayers().forEach(uuid -> {
                // 如果是胜利队伍的玩家
                if (winnerTeam.getPlayerList().contains(uuid)) {
                    this.addPlayerMoney(uuid, reward);
                } else { // 失败队伍的玩家
                    lostTeams.forEach((lostTeam)->{
                        if (lostTeam.getPlayerList().contains(uuid)) {
                            int defaultEconomy = 1400;
                            int compensation = 500;
                            int compensationFactor = lostTeam.getCompensationFactor();
                            // 计算失败补偿
                            int loss = defaultEconomy + compensation * compensationFactor;
                            // 如果玩家没有活着，则给予失败补偿
                            if(!Objects.requireNonNull(lostTeam.getPlayerData(uuid)).getTabData().isLiving()){
                                this.addPlayerMoney(uuid, loss);
                            }
                        }
                    });
                }
            });
            // 检查连败情况
            this.checkLoseStreaks(winnerTeam.name);
            // 同步商店金钱数据
            this.getShops().forEach(FPSMShop::syncShopMoneyData);
    }

    private void checkLoseStreaks(String winnerTeam) {
        // 遍历所有队伍，检查连败情况
        this.getMapTeams().getTeams().forEach(team -> {
            if (team.name.equals(winnerTeam)) {
                // 胜利，连败次数减1
                team.setLoseStreak(Math.max(team.getLoseStreak() - 1,0));
            } else {
                // 失败，连败次数加1
                team.setLoseStreak(team.getLoseStreak() + 1);
            }

            // 更新补偿因数
            int compensationFactor = team.getCompensationFactor();
            if (team.getLoseStreak() > 0) {
                // 连败，补偿因数加1
                compensationFactor = Math.min(compensationFactor + 1, 4);
            }
            team.setCompensationFactor(compensationFactor);
        });
    }

    public void startNewRound() {
        this.isStart = true;
        this.isWaiting = true;
        this.isWaitingWinner = false;
        this.cleanupMap();
        this.getMapTeams().resetLivingPlayers();
        this.getMapTeams().getJoinedPlayers().forEach((uuid -> {
            ServerPlayer serverPlayer = this.getServerLevel().getServer().getPlayerList().getPlayer(uuid);
            if(serverPlayer != null){
                syncNormalRoundStartMessage(serverPlayer);
                serverPlayer.removeAllEffects();
                serverPlayer.addEffect(new MobEffectInstance(MobEffects.SATURATION,-1,2,false,false,false));
                this.teleportPlayerToReSpawnPoint(serverPlayer);
            }
        }));
        this.giveBlastTeamBomb();
        this.getShops().forEach(FPSMShop::syncShopData);
        this.checkMatchPoint();
    }

    public void checkMatchPoint(){
        int ctScore = this.getCTTeam().getScores();
        int tScore = this.getTTeam().getScores();
        if(this.isOvertime){
            int check = winnerRound - 1 - 6 * this.overCount + 4;

            if(ctScore - check == 1 || tScore - check == 1){
                this.sendAllPlayerTitle(Component.translatable("fpsm.map.cs.match.point").withStyle(ChatFormatting.RED),null);
            }
        }else{
            if(ctScore == winnerRound - 1 || tScore == winnerRound - 1){
                this.sendAllPlayerTitle(Component.translatable("fpsm.map.cs.match.point").withStyle(ChatFormatting.RED),null);
            }
        }
    }

    private void syncNormalRoundStartMessage(ServerPlayer serverPlayer) {
        this.sendPacketToJoinedPlayer(serverPlayer, new ShopStatesS2CPacket(true), true);
        var bombResetPacket = new BombDemolitionProgressS2CPacket(0);
        this.sendPacketToJoinedPlayer(serverPlayer, bombResetPacket, true);
        BaseTeam baseTeam = this.getMapTeams().getTeamByPlayer(serverPlayer);
        if(baseTeam != null){
            var packet = new CSGameTabStatsS2CPacket(serverPlayer.getUUID(), Objects.requireNonNull(baseTeam.getPlayerData(serverPlayer.getUUID())).getTabData(),baseTeam.name);
            this.sendPacketToJoinedPlayer(serverPlayer, packet, true);
        }
    }

    @Override
    public void victory() {
        this.getMapTeams().getJoinedPlayers().forEach((uuid -> {
            ServerPlayer serverPlayer = this.getServerLevel().getServer().getPlayerList().getPlayer(uuid);
            if(serverPlayer != null){
                this.sendPacketToJoinedPlayer(serverPlayer,new FPSMatchStatsResetS2CPacket(),true);
                serverPlayer.removeAllEffects();
            }
        }));
        this.checkErrorPlayerTeam();
        resetGame();
    }

    @Override
    public boolean victoryGoal() {
        AtomicBoolean isVictory = new AtomicBoolean(false);
        if(this.isWaitingOverTimeVote){
            return false;
        }
        this.getMapTeams().getTeams().forEach((team) -> {
            if (team.getScores() >= (isOvertime ? winnerRound - 1 + (this.overCount * 3) + 4 : winnerRound)) {
                isVictory.set(true);
                this.getMapTeams().getJoinedPlayers().forEach((uuid -> {
                    ServerPlayer serverPlayer = this.getServerLevel().getServer().getPlayerList().getPlayer(uuid);
                    if (serverPlayer != null) {
                        serverPlayer.connection.send(new ClientboundSetTitleTextPacket(Component.translatable("fpsm.map.cs.winner." + team.name + ".message").withStyle(team.name.equals("ct") ? ChatFormatting.DARK_AQUA : ChatFormatting.YELLOW)));
                    }
                }));
            }
        });
        return isVictory.get() && !this.isDebug();
    }

    public void startOvertimeVote() {
        Component translation = Component.translatable("fpsm.cs.overtime");
        this.startVote("overtime",Component.translatable("fpsm.map.vote.message","System",translation), 20, 0.5f);
    }

    public void startOvertime() {
        this.isOvertime = true;
        this.isWaitingOverTimeVote = false;
        this.isPause = false;
        this.currentPauseTime = 0;
        this.syncShopData();
        this.getMapTeams().getTeams().forEach(team-> team.getPlayers().forEach((uuid, playerData)->{
            playerData.setLiving(false);
            this.setPlayerMoney(uuid, 10000);
        }));
        this.startNewRound();
    }

    // TODO 重要方法
    @Override
    public void cleanupMap() {
        super.cleanupMap();
        this.checkErrorPlayerTeam();
        AreaData areaData = this.getMapArea();
        ServerLevel serverLevel = this.getServerLevel();

        serverLevel.getEntitiesOfClass(Entity.class,areaData.getAABB()).forEach(entity -> {
            if(entity instanceof ItemEntity itemEntity){
                itemEntity.discard();
            }
            if(entity instanceof CompositionC4Entity c4){
                c4.discard();
            }

            if(entity instanceof MatchDropEntity matchDropEntity){
                matchDropEntity.discard();
            }
        });
        AtomicInteger atomicInteger = new AtomicInteger(0);
        int ctScore = this.getCTTeam().getScores();
        int tScore = this.getTTeam().getScores();
        boolean switchFlag;
        if (!isOvertime) {
            // 发起加时赛投票
            if (ctScore == 12 && tScore == 12) {
                this.startOvertimeVote();
                this.setBlasting(0);
                this.setExploded(false);
                this.currentRoundTime = 0;
                this.isPause = true;
                this.currentPauseTime = pauseTime - 500;
                return;
            }else{
                this.getMapTeams().getTeams().forEach((team)-> atomicInteger.addAndGet(team.getScores()));
                if(atomicInteger.get() == 12){
                    switchFlag = true;
                    this.getMapTeams().switchAttackAndDefend(this.getServerLevel(),"t","ct");
                    this.syncShopData();
                } else {
                    switchFlag = false;
                }
                this.currentPauseTime = 0;
            }
        }else{
            // 加时赛换边判断 打满3局换边
            int total = ctScore + tScore;
            int check = total - 24 - 6 * this.overCount;
            if(check % 3 == 0 && check > 0){
                switchFlag = true;
                this.getMapTeams().switchAttackAndDefend(this.getServerLevel(),"t","ct");
                this.syncShopData();
                this.getMapTeams().getJoinedPlayers().forEach((uuid -> this.setPlayerMoney(uuid, 10000)));
                if (check == 6 && ctScore < 12 + 3 * this.overCount + 4 && tScore < 12 + 3 * this.overCount + 4 ) {
                    this.overCount++;
                }
            } else {
                switchFlag = false;
            }
            this.currentPauseTime = 0;
        }

        this.setBlasting(0);
        this.setExploded(false);
        this.currentRoundTime = 0;
        this.isShopLocked = false;
        this.getMapTeams().setTeamsSpawnPoints();
        this.getMapTeams().getJoinedPlayers().forEach((uuid -> {
            ServerPlayer player =  this.getServerLevel().getServer().getPlayerList().getPlayer(uuid);
            if(player != null){
                player.heal(player.getMaxHealth());
                player.setGameMode(GameType.ADVENTURE);
                if(switchFlag){
                    this.clearPlayerInventory(player);
                    this.givePlayerKits(player);
                    this.sendPacketToJoinedPlayer(player,new ClientboundSetTitleTextPacket(Component.translatable("fpsm.map.cs.team.switch").withStyle(ChatFormatting.GREEN)),true);
                }else{
                    boolean isLiving = Objects.requireNonNull(Objects.requireNonNull(this.getMapTeams().getTeamByPlayer(player)).getPlayerTabData(player.getUUID())).isLiving();
                    if(!isLiving) {
                        this.clearPlayerInventory(player);
                        this.givePlayerKits(player);
                    }else{
                        this.resetGunAmmon();
                    }
                    this.getShop(Objects.requireNonNull(this.getMapTeams().getTeamByPlayer(uuid)).name).getPlayerShopData(uuid).lockShopSlots(player);
                }
            }
        }));
        this.getShops().forEach(FPSMShop::syncShopData);
    }

    public void teleportPlayerToReSpawnPoint(ServerPlayer player){
        BaseTeam team = this.getMapTeams().getTeamByPlayer(player);
        if (team == null) return;
        SpawnPointData data = Objects.requireNonNull(team.getPlayerData(player.getUUID())).getSpawnPointsData();
        teleportToPoint(player, data);
    }

    public void teleportPlayerToMatchEndPoint(ServerPlayer player){
        if (this.matchEndTeleportPoint == null ) return;
        SpawnPointData data = this.matchEndTeleportPoint;
        teleportToPoint(player, data);
    }

    private void teleportToPoint(ServerPlayer player, SpawnPointData data) {
        BlockPos pos = data.getPosition();
        if(!Level.isInSpawnableBounds(pos)) return;
        Set<RelativeMovement> set = EnumSet.noneOf(RelativeMovement.class);
        set.add(RelativeMovement.X_ROT);
        set.add(RelativeMovement.Y_ROT);
        if (player.teleportTo(Objects.requireNonNull(this.getServerLevel().getServer().getLevel(data.getDimension())), pos.getX(),pos.getY(),pos.getZ(), set, 0, 0)) {
            label23: {
                if (player.isFallFlying()) {
                    break label23;
                }

                player.setDeltaMovement(player.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D));
                player.setOnGround(true);
            }
        }
    }

    public void giveBlastTeamBomb(){
        BaseTeam team = this.getMapTeams().getTeamByComplexName(this.blastTeam);
        if(team != null){
            Random random = new Random();
            // 随机选择一个玩家作为炸弹携带者
            if(team.getPlayerList().isEmpty()) return;

            team.getPlayerList().forEach((uuid)-> clearPlayerInventory(uuid,(itemStack) -> itemStack.getItem() instanceof CompositionC4));

            UUID uuid = team.getPlayerList().get(random.nextInt(team.getPlayerList().size()));
            if(uuid!= null){
                ServerPlayer player = this.getServerLevel().getServer().getPlayerList().getPlayer(uuid);
                if(player != null){
                    player.addItem(new ItemStack(FPSMItemRegister.C4.get(),1));
                    player.inventoryMenu.broadcastChanges();
                    player.inventoryMenu.slotsChanged(player.getInventory());
                }
            }
        }
    }

    public void clearPlayerInventory(UUID uuid, Predicate<ItemStack> inventoryPredicate){
        Player player = this.getServerLevel().getPlayerByUUID(uuid);
        if(player instanceof ServerPlayer serverPlayer){
            this.clearPlayerInventory(serverPlayer,inventoryPredicate);
        }
    }

    public void clearPlayerInventory(ServerPlayer player, Predicate<ItemStack> predicate){
        player.getInventory().clearOrCountMatchingItems(predicate, -1, player.inventoryMenu.getCraftSlots());
        player.containerMenu.broadcastChanges();
        player.inventoryMenu.slotsChanged(player.getInventory());
    }

    public void clearPlayerInventory(ServerPlayer player){
        player.getInventory().clearOrCountMatchingItems((p_180029_) -> true, -1, player.inventoryMenu.getCraftSlots());
        player.containerMenu.broadcastChanges();
        player.inventoryMenu.slotsChanged(player.getInventory());
    }

    @Override
    public Map<String, List<ItemStack>> getStartKits() {
        return this.startKits;
    }

    public void setPauseState(ServerPlayer player){
        BaseTeam team = this.getMapTeams().getTeamByPlayer(player);
        if(team != null && team.canPause() && this.isStart && !this.isPause){
            team.addPause();
            if(!this.isWaiting){
                this.sendAllPlayerMessage(Component.translatable("fpsm.map.cs.pause.nextRound.success").withStyle(ChatFormatting.GOLD),false);
            }else{
                this.sendAllPlayerMessage(Component.translatable("fpsm.map.cs.pause.success").withStyle(ChatFormatting.GOLD),false);
            }
        }else{
            player.displayClientMessage(Component.translatable("fpsm.map.cs.pause.fail").withStyle(ChatFormatting.RED),false);
        }
    }

    public void setUnPauseState(){
        this.isPause = false;
        this.currentPauseTime = 0;
    }

    private void startUnpauseVote(ServerPlayer serverPlayer) {
        if(this.voteObj == null){
            Component translation = Component.translatable("fpsm.cs.unpause");
            this.startVote("unpause",Component.translatable("fpsm.map.vote.message",serverPlayer.getDisplayName(),translation),15,1f);
            this.voteObj.addAgree(serverPlayer);
        }else{
            Component translation = Component.translatable("fpsm.cs." + this.voteObj.getVoteTitle());
            serverPlayer.displayClientMessage(Component.translatable("fpsm.map.vote.fail.alreadyHasVote", translation).withStyle(ChatFormatting.RED),false);
        }
    }

    public void handleAgreeCommand(ServerPlayer serverPlayer){
        if(this.voteObj != null && !this.voteObj.voteResult.containsKey(serverPlayer.getUUID())){
            this.voteObj.addAgree(serverPlayer);
            this.sendAllPlayerMessage(Component.translatable("fpsm.map.vote.agree",serverPlayer.getDisplayName()).withStyle(ChatFormatting.GREEN),false);
        }
    }

    private void handleDisagreeCommand(ServerPlayer serverPlayer) {
        if(this.voteObj != null && !this.voteObj.voteResult.containsKey(serverPlayer.getUUID())){
            this.voteObj.addDisagree(serverPlayer);
            this.sendAllPlayerMessage(Component.translatable("fpsm.map.vote.disagree",serverPlayer.getDisplayName()).withStyle(ChatFormatting.RED),false);
        }
    }


    public void sendAllPlayerMessage(Component message,boolean actionBar){
        this.getMapTeams().getJoinedPlayers().forEach(uuid -> {
            ServerPlayer serverPlayer = (ServerPlayer) this.getServerLevel().getPlayerByUUID(uuid);
            if(serverPlayer != null){
                serverPlayer.displayClientMessage(message,actionBar);
            }
        });
    }

    public void resetGame() {
        this.getMapTeams().getTeams().forEach(baseTeam -> baseTeam.setScores(0));
        this.isOvertime = false;
        this.isWaitingOverTimeVote = false;
        this.overCount = 0;
        this.isShopLocked = false;
        this.cleanupMap();
        this.syncShopData();
        this.getMapTeams().getJoinedPlayers().forEach((uuid -> {
            ServerPlayer player =  this.getServerLevel().getServer().getPlayerList().getPlayer(uuid);
            if (player != null) {
                player.setGameMode(GameType.ADVENTURE);
                this.teleportPlayerToMatchEndPoint(player);
                this.resetPlayerClientData(player);
                this.getServerLevel().getServer().getScoreboard().removePlayerFromTeam(player.getScoreboardName());
                player.getInventory().clearContent();
                player.removeAllEffects();
            }
        }));
        this.isShopLocked = false;
        this.isError = false;
        this.isStart = false;
        this.isWaiting = false;
        this.isWaitingWinner = false;
        this.isWarmTime = false;
        this.currentRoundTime = 0;
        this.currentPauseTime = 0;
        this.isBlasting = 0;
        this.isExploded = false;
        this.getMapTeams().reset();
    }

    public final void setBlastTeam(String team){
        this.blastTeam = this.getGameType()+"_"+this.getMapName()+"_"+team;
    }

    public boolean checkCanPlacingBombs(String team){
        if(this.blastTeam == null) return false;
        return this.blastTeam.equals(team);
    }

    public boolean checkPlayerIsInBombArea(Player player){
        AtomicBoolean a = new AtomicBoolean(false);
        this.bombAreaData.forEach(area->{
            if(!a.get()) a.set(area.isPlayerInArea(player));
        });
        return a.get();
    }
    @Override
    public ArrayList<ItemStack> getKits(BaseTeam team) {
        return (ArrayList<ItemStack>) this.startKits.get(team.getFixedName());
    }

    @Override
    public void addKits(BaseTeam team, ItemStack itemStack) {
        this.startKits.computeIfAbsent(team.getFixedName(), t -> new ArrayList<>()).add(itemStack);
    }

    @Override
    public void clearTeamKits(BaseTeam team){
        if(this.startKits.containsKey(team.getFixedName())){
            this.startKits.get(team.getFixedName()).clear();
        }
    }

    @Override
    public void setStartKits(Map<String, ArrayList<ItemStack>> kits) {
        kits.forEach((s, list) -> list.forEach((itemStack) -> {
            if(itemStack.getItem() instanceof IGun iGun){
                FPSMUtil.fixGunItem(itemStack, iGun);
            }
        }));

        this.startKits.clear();
        this.startKits.putAll(kits);
    }


    @Override
    public void setAllTeamKits(ItemStack itemStack) {
        this.startKits.values().forEach((v) -> v.add(itemStack));
    }
    public void addBombArea(AreaData area){
        this.bombAreaData.add(area);
    }
    public List<AreaData> getBombAreaData() {
        return bombAreaData;
    }
    public void setBlasting(int blasting) {
        isBlasting = blasting;
    }
    public void setExploded(boolean exploded) {
        isExploded = exploded;
    }

    public int isBlasting() {
        return isBlasting;
    }

    public boolean isExploded() {
        return isExploded;
    }

    public void syncToClient() {
        BaseTeam ct = this.getCTTeam();
        BaseTeam t = this.getTTeam();
        CSGameSettingsS2CPacket packet = new CSGameSettingsS2CPacket(ct.getScores(),t.getScores(), this.currentPauseTime,this.currentRoundTime,this.isDebug(),this.isStart,this.isError,this.isPause,this.isWaiting,this.isWaitingWinner);
        this.getMapTeams().getJoinedPlayers().forEach((uuid -> {
            ServerPlayer player = (ServerPlayer) this.getServerLevel().getPlayerByUUID(uuid);
            if(player != null){
                FPSMatch.INSTANCE.send(PacketDistributor.PLAYER.with(()-> player), packet);
            }
        }));
    }

    public void resetPlayerClientData(ServerPlayer serverPlayer){
        FPSMatchStatsResetS2CPacket packet = new FPSMatchStatsResetS2CPacket();
        FPSMatch.INSTANCE.send(PacketDistributor.PLAYER.with(()-> serverPlayer), packet);
    }

    public void resetGunAmmon(){
        this.getMapTeams().getJoinedPlayers().forEach((uuid)->{
            ServerPlayer serverPlayer = (ServerPlayer) this.getServerLevel().getPlayerByUUID(uuid);
            if(serverPlayer != null){
                FPSMUtil.resetAllGunAmmo(serverPlayer);
            }
        });
    }

    @Nullable
    public SpawnPointData getMatchEndTeleportPoint() {
        return matchEndTeleportPoint;
    }

    public void setMatchEndTeleportPoint(SpawnPointData matchEndTeleportPoint) {
        this.matchEndTeleportPoint = matchEndTeleportPoint;
    }

    @SubscribeEvent
    public static void onPlayerKillOnMap(PlayerKillOnMapEvent event){
        if(event.getBaseMap() instanceof CSGameMap csGameMap){
            BaseTeam killerTeam = csGameMap.getMapTeams().getTeamByPlayer(event.getKiller());
            BaseTeam deadTeam = csGameMap.getMapTeams().getTeamByPlayer(event.getDead());
            if(killerTeam == null || deadTeam == null) return;
            if (killerTeam.getFixedName().equals(deadTeam.getFixedName())){
                csGameMap.removePlayerMoney(event.getKiller().getUUID(),300);
                csGameMap.getShop(killerTeam.name).syncShopMoneyData(event.getKiller().getUUID());
                event.getKiller().displayClientMessage(Component.translatable("fpsm.kill.message.teammate",300),false);
            }else{
                csGameMap.addPlayerMoney(event.getKiller().getUUID(),300);
                csGameMap.getShop(killerTeam.name).syncShopMoneyData(event.getKiller().getUUID());
                event.getKiller().displayClientMessage(Component.translatable("fpsm.kill.message.enemy",300),false);
            }
        }
    }

    @SubscribeEvent
    public static void onChat(ServerChatEvent event){
        BaseMap map = FPSMCore.getInstance().getMapByPlayer(event.getPlayer());
        if(map instanceof CSGameMap csGameMap){
            String[] m = event.getMessage().getString().split("\\.");
            if(m.length > 1){
                csGameMap.handleChatCommand(m[1],event.getPlayer());
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOutEvent(PlayerEvent.PlayerLoggedOutEvent event){
        if(event.getEntity() instanceof ServerPlayer player){
            BaseMap map = FPSMCore.getInstance().getMapByPlayer(player);
            if(map instanceof CSGameMap csGameMap && csGameMap.isStart){
                dropC4(player);
                player.getInventory().clearContent();
                map.sendPacketToAllPlayer(new FPSMatchTabRemovalS2CPacket(player.getUUID()));
            }
        }
    }

    private static void dropC4(ServerPlayer player) {
        int im = player.getInventory().clearOrCountMatchingItems((i) -> i.getItem() instanceof CompositionC4, -1, player.inventoryMenu.getCraftSlots());
        if (im > 0) {
            ItemEntity entity = player.drop(new ItemStack(FPSMItemRegister.C4.get(), 1), false, false);
            if (entity != null) {
                entity.setGlowingTag(true);
            }
            player.getInventory().setChanged();
        }
    }

    @SubscribeEvent
    public static void onPlayerPickupItem(PlayerEvent.ItemPickupEvent event){
        if(event.getEntity().level().isClientSide) return;
        BaseMap map = FPSMCore.getInstance().getMapByPlayer(event.getEntity());
        if (map instanceof ShopMap<?> shopMap) {
            BaseTeam team = map.getMapTeams().getTeamByPlayer(event.getEntity());
            if(team == null) return;
            FPSMShop shop = shopMap.getShop(team.name);
            if (shop == null) return;

            ShopData shopData = shop.getPlayerShopData(event.getEntity().getUUID());
            Pair<ItemType, ShopSlot> pair = shopData.checkItemStackIsInData(event.getStack());
            if(pair != null){
                ShopSlot slot = pair.getSecond();
                slot.lock(event.getStack().getCount());
                shop.syncShopData((ServerPlayer) event.getEntity(),pair.getFirst(),slot);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerDropItem(ItemTossEvent event){
        if(event.getEntity().level().isClientSide) return;
        ItemStack itemStack = event.getEntity().getItem();
        BaseMap map = FPSMCore.getInstance().getMapByPlayer(event.getPlayer());
        if(itemStack.getItem() instanceof CompositionC4){
            event.getEntity().setGlowingTag(true);
        }

        if(itemStack.getItem() instanceof BombDisposalKit){
            event.setCanceled(true);
            event.getPlayer().displayClientMessage(Component.translatable("fpsm.item.bomb_disposal_kit.drop.message").withStyle(ChatFormatting.RED),true);
            event.getPlayer().getInventory().add(new ItemStack(FPSMItemRegister.BOMB_DISPOSAL_KIT.get(),1));
        }

        //商店逻辑
        if (map instanceof ShopMap<?> shopMap){
            BaseTeam team = map.getMapTeams().getTeamByPlayer(event.getPlayer());
            if(team == null) return;
            FPSMShop shop = shopMap.getShop(team.name);
            if (shop == null) return;

            ShopData shopData = shop.getPlayerShopData(event.getEntity().getUUID());
            Pair<ItemType, ShopSlot> pair = shopData.checkItemStackIsInData(itemStack);
            if(pair != null){
                ShopSlot slot = pair.getSecond();
                if(pair.getFirst() != ItemType.THROWABLE){
                    slot.unlock(itemStack.getCount());
                    shop.syncShopData((ServerPlayer) event.getPlayer(),pair.getFirst(),slot);
                }
            }
        }

        MatchDropEntity.DropType type = MatchDropEntity.getItemType(itemStack);
        if(map instanceof CSGameMap && !event.isCanceled() && type != MatchDropEntity.DropType.MISC){
            FPSMCore.playerDropMatchItem((ServerPlayer) event.getPlayer(),itemStack);
            event.setCanceled(true);
        }

    }

    @SubscribeEvent
    public static void onPlayerHurt(LivingHurtEvent event){
        if(event.getEntity() instanceof ServerPlayer serverPlayer){
            BaseMap map = FPSMCore.getInstance().getMapByPlayer(serverPlayer);
            if(map instanceof CSGameMap csGameMap){
                BaseTeam team = csGameMap.getMapTeams().getTeamByPlayer(serverPlayer);
                if(team != null){
                    PlayerData playerData = team.getPlayerData(serverPlayer.getUUID());
                    if(playerData != null){
                        playerData.getTabData().addDamage(event.getAmount());
                        map.sendPacketToAllPlayer(new CSGameTabStatsS2CPacket(serverPlayer.getUUID(), playerData.getTabData(),team.name));
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerKilledByGun(EntityKillByGunEvent event){
        if(event.getLogicalSide() == LogicalSide.SERVER){
            if (event.getKilledEntity() instanceof ServerPlayer player) {
                BaseMap map = FPSMCore.getInstance().getMapByPlayer(player);
                if (map instanceof CSGameMap && map.checkGameHasPlayer(player)) {
                    if(event.getAttacker() instanceof ServerPlayer fromPlayer){
                        BaseMap fromMap = FPSMCore.getInstance().getMapByPlayer(player);
                        if (fromMap != null && fromMap.equals(map)) {
                            if(fromPlayer.getMainHandItem().getItem() instanceof IGun) {
                                BaseTeam team = fromMap.getMapTeams().getTeamByPlayer(fromPlayer);

                                if(event.isHeadShot() && team != null){
                                    TabData tabData = Objects.requireNonNull(team.getPlayerData(fromPlayer.getUUID())).getTabData();
                                    tabData.addHeadshotKill();
                                    map.sendPacketToAllPlayer(new CSGameTabStatsS2CPacket(tabData.getOwner(), tabData,team.name));
                                }

                                DeathMessage deathMessage = new DeathMessage.Builder(fromPlayer, player, fromPlayer.getMainHandItem()).setHeadShot(event.isHeadShot()).build();
                                DeathMessageS2CPacket killMessageS2CPacket = new DeathMessageS2CPacket(deathMessage);
                                fromMap.getMapTeams().getJoinedPlayers().forEach((uuid -> {
                                    ServerPlayer serverPlayer = (ServerPlayer) fromMap.getServerLevel().getPlayerByUUID(uuid);
                                    if(serverPlayer != null){
                                        FPSMatch.INSTANCE.send(PacketDistributor.PLAYER.with(()-> serverPlayer), killMessageS2CPacket);
                                    }
                                }));
                            }
                        }
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerDeathEvent(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BaseMap map = FPSMCore.getInstance().getMapByPlayer(player);
            if (map instanceof CSGameMap csGameMap && map.checkGameHasPlayer(player)) {
                csGameMap.handlePlayerDeath(player,event.getSource().getEntity());
                event.setCanceled(true);
            }
        }
    }

    public void handlePlayerDeath(ServerPlayer player, @Nullable Entity fromEntity) {
        ServerPlayer from = null;
        if (fromEntity instanceof ServerPlayer fromPlayer) {
            BaseMap fromMap = FPSMCore.getInstance().getMapByPlayer(player);
            if (fromMap != null && fromMap.equals(this)) {
                from = fromPlayer;
                if(fromPlayer.getMainHandItem().isEmpty()){
                    DeathMessage message = new DeathMessage.Builder(player,fromPlayer, ItemStack.EMPTY).setArg("hand").build();
                    this.sendPacketToAllPlayer(new DeathMessageS2CPacket(message));
                }
            }
        }
        if(this.isStart) {
            MapTeams teams = this.getMapTeams();
            BaseTeam deadPlayerTeam = teams.getTeamByPlayer(player);
            if (deadPlayerTeam != null) {
                this.getShop(deadPlayerTeam.name).clearPlayerShopData(player.getUUID());
                this.sendPacketToJoinedPlayer(player,new ShopStatesS2CPacket(false),true);
                PlayerData data = deadPlayerTeam.getPlayerData(player.getUUID());
                if (data == null) return;
                data.getTabData().addDeaths();
                data.setLiving(false);

                // 清除c4,并掉落c4
                dropC4(player);

                // 清除拆弹工具,并掉落拆弹工具
                int ik = player.getInventory().clearOrCountMatchingItems((i) -> i.getItem() instanceof BombDisposalKit, -1, player.inventoryMenu.getCraftSlots());
                if (ik > 0) {
                    ItemEntity entity = player.drop(new ItemStack(FPSMItemRegister.BOMB_DISPOSAL_KIT.get(), 1), false, false);
                    if (entity != null) {
                        entity.setGlowingTag(true);
                    }
                    player.getInventory().setChanged();
                }
                FPSMCore.playerDeadDropWeapon(player);
                player.getInventory().clearContent();
                player.heal(player.getMaxHealth());
                player.setGameMode(GameType.SPECTATOR);
                this.setBystander(player);
                this.sendPacketToAllPlayer(new CSGameTabStatsS2CPacket(player.getUUID(), data.getTabData(),deadPlayerTeam.name));
            }


            Map<UUID, Float> hurtDataMap = teams.getLivingHurtData().get(player.getUUID());
            if (hurtDataMap != null && !hurtDataMap.isEmpty()) {

                List<Map.Entry<UUID, Float>> sortedDamageEntries = hurtDataMap.entrySet().stream()
                        .filter(entry -> entry.getValue() > 4)
                        .sorted(Map.Entry.<UUID, Float>comparingByValue().reversed())
                        .limit(2)
                        .toList();

                for (Map.Entry<UUID, Float> sortedDamageEntry : sortedDamageEntries) {
                    UUID assistId = sortedDamageEntry.getKey();
                    ServerPlayer assist = (ServerPlayer) this.getServerLevel().getPlayerByUUID(assistId);
                    if (assist != null && teams.getJoinedPlayers().contains(assistId)) {
                        BaseTeam assistPlayerTeam = teams.getTeamByPlayer(assist);
                        if (assistPlayerTeam != null) {
                            PlayerData assistData = assistPlayerTeam.getPlayerData(assistId);
                            // 如果是击杀者就不添加助攻
                            if (assistData == null || from != null && from.getUUID().equals(assistId)) continue;
                            assistData.getTabData().addAssist();
                            this.sendPacketToAllPlayer( new CSGameTabStatsS2CPacket(assistData.getOwner(), assistData.getTabData(),assistPlayerTeam.name));
                        }
                    }
                }
            }

            if(from == null) return;
            BaseTeam killerPlayerTeam = teams.getTeamByPlayer(from);
            if (killerPlayerTeam != null) {
                PlayerData data = killerPlayerTeam.getPlayerData(from.getUUID());
                if (data == null) return;
                data.getTabData().addKills();
                MinecraftForge.EVENT_BUS.post(new PlayerKillOnMapEvent(this, player, from));
                this.sendPacketToAllPlayer(new CSGameTabStatsS2CPacket(from.getUUID(), data.getTabData(),killerPlayerTeam.name));
            }
        }
    }

    public void handleChatCommand(String rawText,ServerPlayer player){
        COMMANDS.forEach((k,v)->{
            if (rawText.contains(k) && rawText.length() == k.length()){
                v.accept(this,player);
            }
        });
    }

    @Override
    public CSGameMap getMap() {
        return this;
    }
    public static final Codec<CSGameMap> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        // 基础地图数据
        Codec.STRING.fieldOf("mapName").forGetter(CSGameMap::getMapName),
        FPSMCodec.AREA_DATA_CODEC.fieldOf("mapArea").forGetter(CSGameMap::getMapArea),
        ResourceLocation.CODEC.fieldOf("serverLevel").forGetter(map -> map.getServerLevel().dimension().location()),

        // 队伍出生点数据
         FPSMCodec.SPAWN_POINT_DATA_MAP_LIST_CODEC.fieldOf("spawnpoints").forGetter(map->map.getMapTeams().getAllSpawnPoints()),

        // 商店数据 - 使用字符串到FPSMShop的映射
        Codec.unboundedMap(Codec.STRING, FPSMShop.CODEC).fieldOf("shops")
            .forGetter(map -> map.shop),
            
        // 初始装备数据
        FPSMCodec.TEAM_ITEMS_KITS_CODEC.fieldOf("startKits")
            .forGetter(map -> map.startKits),
            
        // 炸弹区域数据
        FPSMCodec.List_AREA_DATA_CODEC.fieldOf("bombAreas")
            .forGetter(map -> map.bombAreaData),
            
        // 爆破队伍
        Codec.STRING.fieldOf("blastTeam")
            .forGetter(map -> map.blastTeam),
            
        // 比赛结束传送点
        FPSMCodec.SPAWN_POINT_DATA_CODEC.optionalFieldOf("matchEndPoint")
            .forGetter(map -> Optional.ofNullable(map.matchEndTeleportPoint))
            
    ).apply(instance, (mapName, mapArea, serverLevel, spawnPoints, shops, startKits, bombAreas, blastTeam, matchEndPoint) -> {
        // 创建新的CSGameMap实例
        CSGameMap gameMap = new CSGameMap(
            FPSMCore.getInstance().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,serverLevel)),
            mapName,
            mapArea
        );

        // 设置类型
        gameMap.setGameType("cs");

        // 设置出生点数据
        gameMap.setMapTeams(new MapTeams(gameMap.getServerLevel(),gameMap.getTeams(),gameMap));
        gameMap.getMapTeams().putAllSpawnPoints(spawnPoints);

        // 设置商店数据
        gameMap.shop.clear();
        gameMap.shop.putAll(shops);
        
        // 设置初始装备
        Map<String, ArrayList<ItemStack>> data = new HashMap<>();
        startKits.forEach((t,l)->{
            ArrayList<ItemStack> list = new ArrayList<>(l);
            data.put(t,list);
        });
        gameMap.setStartKits(data);
        
        // 设置炸弹区域
        gameMap.bombAreaData.clear();
        gameMap.bombAreaData.addAll(bombAreas);
        
        // 设置爆破队伍
        gameMap.blastTeam = blastTeam;
        
        // 设置比赛结束传送点
        matchEndPoint.ifPresent(point -> gameMap.matchEndTeleportPoint = point);
        
        return gameMap;
    }));

    @Override
    public Codec<CSGameMap> codec() {
        return CODEC;
    }


    public BaseTeam getTTeam(){
        return this.getMapTeams().getTeamByName("t");
    }
    public BaseTeam getCTTeam(){
        return this.getMapTeams().getTeamByName("ct");
    }
    public int getStartMoney() {
        return startMoney;
    }

    public void setStartMoney(int startMoney) {
        this.startMoney = startMoney;
    }

    public int getWaitingTime() {
        return waitingTime;
    }

    public void setWaitingTime(int waitingTime) {
        this.waitingTime = waitingTime;
    }

    public int getRoundTimeLimit() {
        return roundTimeLimit;
    }

    public void setRoundTimeLimit(int roundTimeLimit) {
        this.roundTimeLimit = roundTimeLimit;
    }

    public int getAutoStartTime() {
        return autoStartTime;
    }

    public void setAutoStartTime(int autoStartTime) {
        this.autoStartTime = autoStartTime;
    }


    public void read() {
        FPSMCore.getInstance().registerMap(this.gameType,this);
    }
    public enum WinnerReason{
        TIME_OUT(3250),
        ACED(3250),
        DEFUSE_BOMB(3500),
        DETONATE_BOMB(3500);
        public final int winMoney;

        WinnerReason(int winMoney) {
            this.winMoney = winMoney;
        }
    }
}
