package com.example.shulkerhl;

import com.mojang.brigadier.Command;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

public class ShulkerHlClient implements ClientModInitializer {
    private static final double MAX_DIST = 100.0;
    private static final double FILL_MIN = 10.0;
    private static final int LIME = 0xFFB7FF00;
    private static final int LIME_FILL = 0xB0B7FF00;
    private static final int MAX_BOXES = 400;

    private boolean enabled = true;
    private List<BlockPos> cache = List.of();
    private int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        HudRenderCallback.EVENT.register(this::render);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("shulkerhl").executes(ctx -> {
                enabled = !enabled;
                ctx.getSource().sendFeedback(Component.literal(
                        "Shulker Highlight: " + (enabled ? "ВКЛ" : "ВЫКЛ")));
                return Command.SINGLE_SUCCESS;
            }));
        });
    }

    private static boolean singleplayerOnly(Minecraft mc) {
        return true;
    }

    private void tick(Minecraft mc) {
        if (++tickCounter % 10 != 0) {
            return;
        }
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (!enabled || player == null || level == null || !singleplayerOnly(mc)) {
            cache = List.of();
            return;
        }
        int pcx = player.blockPosition().getX() >> 4;
        int pcz = player.blockPosition().getZ() >> 4;
        int range = (int) (MAX_DIST / 16.0) + 1;
        double maxSq = MAX_DIST * MAX_DIST;
        List<BlockPos> found = new ArrayList<>();

        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                ChunkAccess chunk = level.getChunk(pcx + dx, pcz + dz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }

                // ИСПРАВЛЕНО: В 1.21.11 у ChunkAccess нет getBlockEntities(), перебираем позиции
                for (BlockPos bePos : chunk.getBlockEntitiesPos()) {
                    if (chunk.getBlockEntity(bePos) instanceof ShulkerBoxBlockEntity) {
                        if (bePos.distSqr(player.blockPosition()) <= maxSq && found.size() < MAX_BOXES) {
                            found.add(bePos.immutable());
                        }
                    }
                }
            }
        }
        cache = found;
    }

    private void render(GuiGraphics g, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.player == null || mc.level == null || mc.screen != null || !singleplayerOnly(mc)) {
            return;
        }
        List<BlockPos> list = cache;
        if (list.isEmpty()) {
            return;
        }

        Camera cam = mc.gameRenderer.getMainCamera();

        // ИСПРАВЛЕНО: В 1.21.11 у Camera используются getPosition(), getYRot() и getXRot()
        Vec3 cp = cam.getPosition();
        double yaw = Math.toRadians(cam.getYRot());
        double pitch = Math.toRadians(cam.getXRot());

        double fx = -Math.sin(yaw) * Math.cos(pitch);
        double fy = -Math.sin(pitch);
        double fz = Math.cos(yaw) * Math.cos(pitch);
        double rx = -Math.cos(yaw);
        double rz = -Math.sin(yaw);
        double ux = -rz * fy;
        double uy = rz * fx - rx * fz;
        double uz = rx * fy;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        double tanHalfF = Math.tan(Math.toRadians(mc.options.fov().get()));
        double aspect = (double) w / (double) h;

        for (BlockPos pos : list) {
            double cx = pos.getX() + 0.5, cy = pos.getY() + 0.5, cz = pos.getZ() + 0.5;
            double dist = Math.sqrt((cx - cp.x) * (cx - cp.x) + (cy - cp.y) * (cy - cp.y) + (cz - cp.z) * (cz - cp.z));

            if (dist > MAX_DIST) {
                continue;
            }

            boolean fill = dist >= FILL_MIN;
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            boolean behind = false;

            for (int i = 0; i < 8 && !behind; i++) {
                double vx = pos.getX() + (i & 1) - cp.x;
                double vy = pos.getY() + ((i >> 1) & 1) - cp.y;
                double vz = pos.getZ() + ((i >> 2) & 1) - cp.z;
                double depth = vx * fx + vy * fy + vz * fz;
                if (depth < 0.05) {
                    behind = true;
                    break;
                }
                double sx = ((vx * rx + vz * rz) / depth) / (tanHalfF * aspect);
                double sy = ((vx * ux + vy * uy + vz * uz) / depth) / tanHalfF;
                double px = (sx * 0.5 + 0.5) * w;
                double py = (0.5 - sy * 0.5) * h;
                minX = Math.min(minX, px); maxX = Math.max(maxX, px);
                minY = Math.min(minY, py); maxY = Math.max(maxY, py);
            }

            if (behind || maxX < 0 || maxY < 0 || minX > w || minY > h) {
                continue;
            }

            int x1 = (int) Math.floor(Math.max(0, minX));
            int y1 = (int) Math.floor(Math.max(0, minY));
            int x2 = (int) Math.ceil(Math.min(w, maxX));
            int y2 = (int) Math.ceil(Math.min(h, maxY));

            if (x2 - x1 < 2 || y2 - y1 < 2) {
                x2 = x1 + 2;
                y2 = y1 + 2;
            }

            if (fill) {
                g.fill(x1, y1, x2, y2, LIME_FILL);
            }
            int t = fill ? 1 : 2;
            g.fill(x1, y1, x2, y1 + t, LIME);
            g.fill(x1, y2 - t, x2, y2, LIME);
            g.fill(x1, y1, x1 + t, y2, LIME);
            g.fill(x2 - t, y1, x2, y2, LIME);
        }
    }
}
