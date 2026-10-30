package com.example.shulkerhl;

import com.mojang.brigadier.Command;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

public class ShulkerHlClient implements ClientModInitializer {
    private static final double MAX_HORIZONTAL = 100.0;
    private static final double FILL_MIN = 10.0;
    private static final double OFF_MAX = 5.0;
    private static final double BEAM_TOP_Y = 320.0;
    private static final double BEAM_BOTTOM_Y = -64.0;
    private static final int MAX_BEAMS = 40;
    
    // Цвета
    private static final int RED_LINE = 0xFFFF0000;    // Ярко-красный контур
    private static final int RED_FILL = 0x60FF0000;    // Полупрозрачная красная заливка
    private static final int BLUE_BEAM = 0xCC0000FF;   // Ярко-синий луч
    private static final int MAX_BOXES = 400;

    private boolean enabled = true;
    private List<BlockPos> cache = List.of();
    private int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        HudRenderCallback.EVENT.register(this::render);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("shulkerhl").executes(ctx -> {
                enabled = !enabled;
                ctx.getSource().sendFeedback(Component.literal(
                    "Shulker Highlight: " + (enabled ? "ВКЛ" : "ВЫКЛ") + " (только одиночный мир)"));
                return Command.SINGLE_SUCCESS;
            })));
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
        int range = (int) (MAX_HORIZONTAL / 16.0) + 1;
        double maxSq = MAX_HORIZONTAL * MAX_HORIZONTAL;
        List<BlockPos> found = new ArrayList<>();
        
        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                ChunkAccess access = level.getChunk(pcx + dx, pcz + dz, ChunkStatus.FULL, false);
                if (!(access instanceof LevelChunk chunk)) {
                    continue;
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof ShulkerBoxBlockEntity) {
                        BlockPos pos = be.getBlockPos();
                        double ddx = pos.getX() + 0.5 - player.getX();
                        double ddz = pos.getZ() + 0.5 - player.getZ();
                        if (ddx * ddx + ddz * ddz <= maxSq && found.size() < MAX_BOXES) {
                            found.add(pos.immutable());
                        }
                    }
                }
            }
        }
        BlockPos here = player.blockPosition();
        found.sort(Comparator.comparingDouble(p -> p.distSqr(here)));
        cache = found;
    }

    private static boolean clip(double[] p, int w, int h) {
        double x1 = p[0], y1 = p[1], x2 = p[2], y2 = p[3];
        double dx = x2 - x1, dy = y2 - y1;
        double t0 = 0.0, t1 = 1.0;
        double[] pp = {-dx, dx, -dy, dy};
        double[] qq = {x1, w - x1, y1, h - y1};
        for (int i = 0; i < 4; i++) {
            if (pp[i] == 0.0) {
                if (qq[i] < 0.0) return false;
            } else {
                double r = qq[i] / pp[i];
                if (pp[i] < 0.0) {
                    if (r > t1) return false;
                    if (r > t0) t0 = r;
                } else {
                    if (r < t0) return false;
                    if (r < t1) t1 = r;
                }
            }
        }
        p[0] = x1 + t0 * dx;
        p[1] = y1 + t0 * dy;
        p[2] = x1 + t1 * dx;
        p[3] = y1 + t1 * dy;
        return true;
    }

    private static void drawBeam(GuiGraphics g, double x1, double y1, double x2, double y2) {
        double ddx = x2 - x1, ddy = y2 - y1;
        if (Math.abs(ddy) >= Math.abs(ddx)) {
            int steps = Math.max(1, (int) Math.ceil(Math.abs(ddy) / 4.0));
            for (int i = 0; i < steps; i++) {
                double ta = i / (double) steps, tb = (i + 1) / (double) steps;
                double xa = x1 + ddx * ta, xb = x1 + ddx * tb;
                double ya = y1 + ddy * ta, yb = y1 + ddy * tb;
                g.fill((int) Math.floor(Math.min(xa, xb)) - 1, (int) Math.floor(Math.min(ya, yb)),
                       (int) Math.ceil(Math.max(xa, xb)) + 1, (int) Math.ceil(Math.max(ya, yb)) + 1, BLUE_BEAM);
            }
        } else {
            int steps = Math.max(1, (int) Math.ceil(Math.abs(ddx) / 4.0));
            for (int i = 0; i < steps; i++) {
                double ta = i / (double) steps, tb = (i + 1) / (double) steps;
                double xa = x1 + ddx * ta, xb = x1 + ddx * tb;
                double ya = y1 + ddy * ta, yb = y1 + ddy * tb;
                g.fill((int) Math.floor(Math.min(xa, xb)), (int) Math.floor(Math.min(ya, yb)) - 1,
                       (int) Math.ceil(Math.max(xa, xb)) + 1, (int) Math.ceil(Math.max(ya, yb)) + 1, BLUE_BEAM);
            }
        }
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
        Vec3 cp = mc.player.getEyePosition();
        double yaw = Math.toRadians(mc.player.getYRot());
        double pitch = Math.toRadians(mc.player.getXRot());
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
        double tanHalf = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
        double aspect = (double) w / (double) h;
        int beams = 0;

        for (BlockPos pos : list) {
            double cx = pos.getX() + 0.5, cy = pos.getY() + 0.5, cz = pos.getZ() + 0.5;
            double dist = Math.sqrt((cx - cp.x) * (cx - cp.x) + (cy - cp.y) * (cy - cp.y) + (cz - cp.z) * (cz - cp.z));
            double hdx = cx - cp.x;
            double hdz = cz - cp.z;
            if (Math.sqrt(hdx * hdx + hdz * hdz) > MAX_HORIZONTAL || dist <= OFF_MAX) {
                continue;
            }
            boolean fill = dist >= FILL_MIN;

            // --- Синий луч ---
            if (fill && beams < MAX_BEAMS) {
                double ax = hdx * rx + hdz * rz;
                double az = hdx * fx + hdz * fz;
                double aTopY = BEAM_TOP_Y - cp.y, aBotY = BEAM_BOTTOM_Y - cp.y;
                double[] t = {ax, hdx * ux + aTopY * uy + hdz * uz, az + aTopY * fy};
                double[] b = {ax, hdx * ux + aBotY * uy + hdz * uz, az + aBotY * fy};
                double near = 0.1;
                if (!(t[2] < near && b[2] < near)) {
                    if (t[2] < near) {
                        double k = (near - t[2]) / (b[2] - t[2]);
                        t = new double[] {t[0] + k * (b[0] - t[0]), t[1] + k * (b[1] - t[1]), near};
                    } else if (b[2] < near) {
                        double k = (near - b[2]) / (t[2] - b[2]);
                        b = new double[] {b[0] + k * (t[0] - b[0]), b[1] + k * (t[1] - b[1]), near};
                    }
                    double[] seg = {
                        ((t[0] / t[2]) / (tanHalf * aspect) * 0.5 + 0.5) * w,
                        (0.5 - (t[1] / t[2]) / tanHalf * 0.5) * h,
                        ((b[0] / b[2]) / (tanHalf * aspect) * 0.5 + 0.5) * w,
                        (0.5 - (b[1] / b[2]) / tanHalf * 0.5) * h
                    };
                    if (clip(seg, w, h)) {
                        drawBeam(g, seg[0], seg[1], seg[2], seg[3]);
                        beams++;
                    }
                }
            }

            // --- Красная рамка вокруг шалкера ---
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
                double sx = ((vx * rx + vz * rz) / depth) / (tanHalf * aspect);
                double sy = ((vx * ux + vy * uy + vz * uz) / depth) / tanHalf;
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
            
            // Делаем минимальный размер рамки 4х4 пикселя, чтобы на большом расстоянии шалкер не превращался в точку
            if (x2 - x1 < 4) {
                int mid = (x1 + x2) / 2;
                x1 = mid - 2;
                x2 = mid + 2;
            }
            if (y2 - y1 < 4) {
                int mid = (y1 + y2) / 2;
                y1 = mid - 2;
                y2 = mid + 2;
            }
            
            if (fill) {
                g.fill(x1, y1, x2, y2, RED_FILL);
            }
            int th = 2; // Делаем рамку толще (2 пикселя) для яркости
            g.fill(x1, y1, x2, y1 + th, RED_LINE);
            g.fill(x1, y2 - th, x2, y2, RED_LINE);
            g.fill(x1, y1, x1 + th, y2, RED_LINE);
            g.fill(x2 - th, y1, x2, y2, RED_LINE);
        }
    }
}
