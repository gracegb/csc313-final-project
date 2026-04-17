import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBEasyFont;
import java.nio.IntBuffer;
import org.lwjgl.stb.STBImage;
import static org.lwjgl.system.MemoryStack.stackPush;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;

final class GameUIRenderer {
    private final FightingGameLWJGL game;
    int title;

    GameUIRenderer(FightingGameLWJGL game) {
        this.game = game;
    }

    void drawCharSelectOverlay() {
        beginOverlay();

        drawRectPx(20, 20, FightingGameLWJGL.WIDTH - 40, 48, 0.07f, 0.09f, 0.13f, 0.82f);
        if (game.gameMode == FightingGameLWJGL.GameMode.SINGLE_PLAYER) {
            drawText(36, 36, "CHARACTER SELECT - SINGLE PLAYER | P1 A/D + F READY | P2 IS AI", 1.0f, 1.0f, 1.0f);
        } else {
            drawText(36, 36, "CHARACTER SELECT - MULTIPLAYER | P1 A/D + F READY | P2 LEFT/RIGHT + SHIFT READY", 1.0f, 1.0f, 1.0f);
        }
        drawText(36, 56, "SESSION: " + game.networkStatus, 0.78f, 0.86f, 0.97f);

        drawRectPx(80, 620, 430, 34, game.p1.ready ? 0.30f : 0.78f, game.p1.ready ? 0.86f : 0.50f, game.p1.ready ? 0.48f : 0.36f, 0.95f);
        drawRectPx(FightingGameLWJGL.WIDTH - 510, 620, 430, 34, game.p2.ready ? 0.30f : 0.78f, game.p2.ready ? 0.86f : 0.50f, game.p2.ready ? 0.48f : 0.36f, 0.95f);

        drawText(92, 643, "P1: " + game.models.get(game.p1.selected).name() + (game.p1.ready ? " READY" : " NOT READY"), 0.08f, 0.08f, 0.08f);
        String p2Label = game.gameMode == FightingGameLWJGL.GameMode.SINGLE_PLAYER
            ? "P2(AI): " + game.models.get(game.p2.selected).name() + " READY"
            : "P2: " + game.models.get(game.p2.selected).name() + (game.p2.ready ? " READY" : " NOT READY");
        drawText(FightingGameLWJGL.WIDTH - 498, 643, p2Label, 0.08f, 0.08f, 0.08f);

        drawCharacterPortraitPlaceholders();

        endOverlay();
    }

    void drawFightOverlay() {
        beginOverlay();

        drawRectPx(20, 20, 430, 26, 0.15f, 0.18f, 0.25f, 0.9f);
        drawRectPx(FightingGameLWJGL.WIDTH - 450, 20, 430, 26, 0.15f, 0.18f, 0.25f, 0.9f);

        drawRectPx(22, 22, (int) (426.0f * (game.p1.health / (float) FightingGameLWJGL.MAX_HEALTH)), 22, 0.30f, 0.86f, 0.52f, 0.95f);
        drawRectPx(FightingGameLWJGL.WIDTH - 448, 22, (int) (426.0f * (game.p2.health / (float) FightingGameLWJGL.MAX_HEALTH)), 22, 0.92f, 0.46f, 0.40f, 0.95f);

        drawText(25, 17, "P1 " + game.models.get(game.p1.selected).name() + " HP " + game.p1.health, 1.0f, 1.0f, 1.0f);
        drawText(FightingGameLWJGL.WIDTH - 445, 17, "P2 " + game.models.get(game.p2.selected).name() + " HP " + game.p2.health, 1.0f, 1.0f, 1.0f);

        drawRectPx(20, FightingGameLWJGL.HEIGHT - 62, FightingGameLWJGL.WIDTH - 40, 42, 0.05f, 0.07f, 0.10f, 0.86f);
        if (game.gameMode == FightingGameLWJGL.GameMode.SINGLE_PLAYER) {
            drawText(34, FightingGameLWJGL.HEIGHT - 36, "P1 MOVE WASD | F PUNCH | R KICK | G BLOCK     P2 AI CONTROLLED", 0.93f, 0.95f, 0.99f);
        } else {
            drawText(34, FightingGameLWJGL.HEIGHT - 36, "P1 MOVE WASD | F PUNCH | R KICK | G BLOCK     P2 MOVE ARROWS | . PUNCH | SHIFT KICK | K BLOCK", 0.93f, 0.95f, 0.99f);
        }
        drawText(34, FightingGameLWJGL.HEIGHT - 52, "SESSION: " + game.networkStatus, 0.78f, 0.86f, 0.97f);
        drawStunMessage(game.p1);
        drawStunMessage(game.p2);

        if (game.winner != 0) {
            drawRectPx(330, 250, 620, 180, 0.04f, 0.05f, 0.07f, 0.92f);
            drawRectPx(340, 260, 600, 160, game.winner == 1 ? 0.22f : 0.34f, 0.24f, game.winner == 2 ? 0.22f : 0.34f, 0.90f);
            drawText(530, 318, "PLAYER " + game.winner + " WINS", 1.0f, 1.0f, 1.0f);
            drawText(430, 356, "PRESS ENTER TO RETURN TO CHARACTER SELECT", 0.94f, 0.94f, 0.98f);
        }

        endOverlay();
    }

    void drawModeSelectOverlay() {
        beginOverlay();
        int y = 270;
        int boxW = 250;
        int boxH = 55;
        int leftX = 150;
        float singleX = (FightingGameLWJGL.WIDTH - boxW) / 2 + boxW / 2 - 30;

        drawText(singleX - 15, y + 130, "SELECT GAME MODE", 0.96f, 0.98f, 1.0f);
        drawText(950, 705, "A/D OR LEFT/RIGHT TO SWITCH  |  ENTER OR F TO CONFIRM", 0.82f, 0.90f, 0.98f);

        boolean singleSelected = game.modeSelectionIndex == 0;
        boolean multiSelected = game.modeSelectionIndex == 1;

        drawRectPx((FightingGameLWJGL.WIDTH - boxW) / 2, y + 155, boxW, boxH, singleSelected ? 0.18f : 0.08f, singleSelected ? 0.62f : 0.14f, singleSelected ? 0.36f : 0.20f, 0.95f);
        drawRectPx((FightingGameLWJGL.WIDTH - boxW) / 2, y + 255, boxW, boxH, multiSelected ? 0.19f : 0.08f, multiSelected ? 0.40f : 0.14f, multiSelected ? 0.66f : 0.20f, 0.95f);

        if (singleSelected) {
            drawRectOutlinePx((FightingGameLWJGL.WIDTH - boxW) / 2, y + 155, boxW, boxH, 3, 0.46f, 0.93f, 0.66f, 0.98f);
        }
        if (multiSelected) {
            drawRectOutlinePx((FightingGameLWJGL.WIDTH - boxW) / 2, y + 255, boxW, boxH, 3, 0.54f, 0.78f, 0.98f, 0.98f);
        }

        int w = 950;
        int h = w / 2;

        drawTexturePx(
                FightingGameLWJGL.WIDTH / 2 - w / 2 + 19,
                -5,
                w,
                h,
                title,
                1.0f
        );

        drawText(singleX - 4, y + 174, "SINGLE PLAYER", 0.95f, 1.0f, 0.97f);
        drawText(singleX - 80, y + 187, "LOCAL HUMAN VS AI-CONTROLLED OPPONENT", 0.83f, 0.92f, 0.88f);

        drawText(singleX, y + 273, "MULTIPLAYER", 0.95f, 0.98f, 1.0f);
        drawText(singleX - 80, y + 289, "LOCAL 2P OR NETWORK HOST/JOIN SESSION", 0.84f, 0.90f, 0.97f);

        drawText(15, 705, "CURRENT SESSION: " + game.networkStatus, 0.79f, 0.87f, 0.97f);

        endOverlay();
    }

    void drawMultiplayerRoleSelectOverlay() {
        beginOverlay();

        drawRectPx(140, 90, FightingGameLWJGL.WIDTH - 280, 110, 0.05f, 0.07f, 0.10f, 0.90f);
        drawText(280, 140, "MULTIPLAYER - SELECT HOST OR JOIN", 0.96f, 0.98f, 1.0f);
        drawText(220, 172, "A/D OR LEFT/RIGHT TO SWITCH  |  ENTER OR F TO CONFIRM", 0.82f, 0.90f, 0.98f);

        int y = 270;
        int boxW = 460;
        int boxH = 120;
        int leftX = 150;
        int rightX = FightingGameLWJGL.WIDTH - leftX - boxW;
        boolean hostSelected = game.multiplayerRoleIndex == 0;
        boolean joinSelected = game.multiplayerRoleIndex == 1;

        drawRectPx(leftX, y, boxW, boxH, hostSelected ? 0.20f : 0.08f, hostSelected ? 0.43f : 0.14f, hostSelected ? 0.64f : 0.20f, 0.95f);
        drawRectPx(rightX, y, boxW, boxH, joinSelected ? 0.21f : 0.08f, joinSelected ? 0.60f : 0.14f, joinSelected ? 0.34f : 0.20f, 0.95f);

        if (hostSelected) drawRectOutlinePx(leftX - 4, y - 4, boxW + 8, boxH + 8, 3, 0.60f, 0.82f, 1.0f, 0.98f);
        if (joinSelected) drawRectOutlinePx(rightX - 4, y - 4, boxW + 8, boxH + 8, 3, 0.58f, 0.98f, 0.76f, 0.98f);

        drawText(leftX + 190, y + 50, "HOST GAME", 0.95f, 0.98f, 1.0f);
        drawText(leftX + 72, y + 82, "CREATE LOBBY AND WAIT FOR PLAYER 2", 0.84f, 0.90f, 0.98f);
        drawText(leftX + 120, y + 106, "PORT: " + game.joinPort, 0.80f, 0.87f, 0.96f);

        drawText(rightX + 198, y + 50, "JOIN GAME", 0.95f, 1.0f, 0.96f);
        drawText(rightX + 84, y + 82, "CONNECT TO HOST AND BECOME PLAYER 2", 0.86f, 0.94f, 0.88f);
        drawText(rightX + 86, y + 106, "TARGET: " + game.joinHost + ":" + game.joinPort, 0.82f, 0.92f, 0.86f);

        drawRectPx(180, FightingGameLWJGL.HEIGHT - 120, FightingGameLWJGL.WIDTH - 360, 60, 0.06f, 0.08f, 0.12f, 0.88f);
        drawText(208, FightingGameLWJGL.HEIGHT - 84, "SESSION: " + game.networkStatus, 0.79f, 0.87f, 0.97f);

        endOverlay();
    }

    void drawMultiplayerWaitingOverlay() {
        beginOverlay();

        drawRectPx(220, 170, FightingGameLWJGL.WIDTH - 440, 300, 0.04f, 0.06f, 0.10f, 0.92f);
        drawRectPx(240, 190, FightingGameLWJGL.WIDTH - 480, 260, 0.10f, 0.14f, 0.22f, 0.90f);

        if (game.networkMode == FightingGameLWJGL.NetworkMode.HOST) {
            drawText(340, 268, "WAITING FOR PLAYER TO CONNECT", 0.94f, 0.97f, 1.0f);
            drawText(352, 305, "SHARE THIS ADDRESS WITH PLAYER 2", 0.84f, 0.91f, 0.98f);
            drawText(396, 344, "HOST PORT: " + game.joinPort, 0.82f, 0.90f, 0.98f);
        } else if (game.networkMode == FightingGameLWJGL.NetworkMode.CLIENT) {
            drawText(380, 268, "JOINING HOST...", 0.94f, 1.0f, 0.94f);
            drawText(326, 305, "WAITING FOR HOST TO UNLOCK CHARACTER SELECT", 0.84f, 0.95f, 0.86f);
            drawText(332, 344, "TARGET: " + game.joinHost + ":" + game.joinPort, 0.82f, 0.93f, 0.85f);
        } else {
            drawText(370, 286, "MULTIPLAYER NOT STARTED", 0.96f, 0.95f, 1.0f);
        }

        drawText(274, 414, "STATUS: " + game.networkStatus, 0.82f, 0.89f, 0.98f);
        endOverlay();
    }

    private void drawCharacterPortraitPlaceholders() {
        int tileSize = 92;
        int tileGap = 12;
        int rowY = FightingGameLWJGL.HEIGHT - 158;
        int totalWidth = game.models.size() * tileSize + (game.models.size() - 1) * tileGap;
        int startX = (FightingGameLWJGL.WIDTH - totalWidth) / 2;

        drawRectPx(startX - 16, rowY - 34, totalWidth + 32, tileSize + 48, 0.05f, 0.07f, 0.10f, 0.84f);
        drawText(startX - 2, rowY - 16, "2D PORTRAIT PLACEHOLDERS", 0.92f, 0.94f, 0.98f);

        for (int i = 0; i < game.models.size(); i++) {
            int x = startX + i * (tileSize + tileGap);
            boolean p1Selected = i == game.p1.selected;
            boolean p2Selected = i == game.p2.selected;
            String modelName = game.models.get(i).name();
            int portraitTex = game.portraitTextures.getOrDefault(FightingGameLWJGL.normalizePortraitKey(modelName), 0);

            drawRectPx(x, rowY, tileSize, tileSize, 0.14f, 0.17f, 0.23f, 0.95f);
            drawRectPx(x + 5, rowY + 5, tileSize - 10, tileSize - 10, 0.22f, 0.26f, 0.34f, 0.92f);
            if (portraitTex != 0) {
                drawTexturePx(x + 5, rowY + 5, tileSize - 10, tileSize - 10, portraitTex, 1.0f);
            } else {
                drawText(x + 16, rowY + 44, "PNG", 0.93f, 0.95f, 0.99f);
            }
            drawText(x + 8, rowY + tileSize + 18, modelName, 0.90f, 0.92f, 0.98f);

            if (p1Selected) {
                drawRectOutlinePx(x - 3, rowY - 3, tileSize + 6, tileSize + 6, 2, 0.33f, 0.88f, 0.55f, 0.98f);
                drawText(x + 4, rowY - 10, "P1", 0.33f, 0.88f, 0.55f);
            }
            if (p2Selected) {
                drawRectOutlinePx(x - 3, rowY - 3, tileSize + 6, tileSize + 6, 2, 0.98f, 0.62f, 0.36f, 0.98f);
                drawText(x + tileSize - 26, rowY - 10, "P2", 0.98f, 0.62f, 0.36f);
            }
        }
    }

    private void drawStunMessage(FightingGameLWJGL.Fighter fighter) {
        if (fighter.stunTimer <= 0) return;
        float sx = FightingGameLWJGL.WIDTH * 0.5f + (fighter.x / FightingGameLWJGL.ARENA_HALF_WIDTH) * 340.0f;
        float sy = FightingGameLWJGL.HEIGHT * 0.42f + (fighter.z / FightingGameLWJGL.ARENA_HALF_DEPTH) * 95.0f;
        drawText(sx - 38.0f, sy - 18.0f, "STUNNED", 1.0f, 0.86f, 0.26f);
    }

    private void beginOverlay() {
        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glLoadIdentity();
        glOrtho(0.0, FightingGameLWJGL.WIDTH, FightingGameLWJGL.HEIGHT, 0.0, -1.0, 1.0);

        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glLoadIdentity();

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);
    }

    private void endOverlay() {
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glDisable(GL_BLEND);

        glMatrixMode(GL_MODELVIEW);
        glPopMatrix();

        glMatrixMode(GL_PROJECTION);
        glPopMatrix();
    }

    private void drawRectPx(int x, int y, int w, int h, float r, float g, float b, float a) {
        if (w <= 0 || h <= 0) return;
        glColor4f(r, g, b, a);
        glBegin(GL_QUADS);
        glVertex2f(x, y);
        glVertex2f(x + w, y);
        glVertex2f(x + w, y + h);
        glVertex2f(x, y + h);
        glEnd();
    }

    private void drawRectOutlinePx(int x, int y, int w, int h, int thickness, float r, float g, float b, float a) {
        if (w <= 0 || h <= 0 || thickness <= 0) return;
        drawRectPx(x, y, w, thickness, r, g, b, a);
        drawRectPx(x, y + h - thickness, w, thickness, r, g, b, a);
        drawRectPx(x, y + thickness, thickness, h - (thickness * 2), r, g, b, a);
        drawRectPx(x + w - thickness, y + thickness, thickness, h - (thickness * 2), r, g, b, a);
    }

    private void drawTexturePx(int x, int y, int w, int h, int textureId, float alpha) {
        if (w <= 0 || h <= 0 || textureId == 0) return;
        glEnable(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, textureId);
        glColor4f(1.0f, 1.0f, 1.0f, alpha);
        glBegin(GL_QUADS);
        glTexCoord2f(0.0f, 0.0f);
        glVertex2f(x, y);
        glTexCoord2f(1.0f, 0.0f);
        glVertex2f(x + w, y);
        glTexCoord2f(1.0f, 1.0f);
        glVertex2f(x + w, y + h);
        glTexCoord2f(0.0f, 1.0f);
        glVertex2f(x, y + h);
        glEnd();
        glBindTexture(GL_TEXTURE_2D, 0);
        glDisable(GL_TEXTURE_2D);
    }

    private void drawText(float x, float y, String text, float r, float g, float b) {
        ByteBuffer textBuffer = BufferUtils.createByteBuffer(text.length() * 270);
        int quads = STBEasyFont.stb_easy_font_print(x, y, text, null, textBuffer);

        glDisable(GL_TEXTURE_2D);
        glColor3f(r, g, b);
        glEnableClientState(GL_VERTEX_ARRAY);
        glVertexPointer(2, GL_FLOAT, 16, textBuffer);
        glDrawArrays(GL_QUADS, 0, quads * 4);
        glDisableClientState(GL_VERTEX_ARRAY);
    }

    private int loadTexture(String path) {
        int width, height;
        ByteBuffer image;

        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);

            image = org.lwjgl.stb.STBImage.stbi_load(path, w, h, channels, 4);
            if (image == null) {
                throw new RuntimeException("Failed to load: " + path);
            }

            width = w.get();
            height = h.get();
        }

        int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);

        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, image);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        STBImage.stbi_image_free(image);

        return textureId;
    }

    void init() {
        title = loadTexture("assets/portraits/titleF.png");
    }


}
