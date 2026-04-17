import static org.lwjgl.glfw.GLFW.glfwGetTime;
import static org.lwjgl.opengl.GL11.*;

final class GameRenderer {
    private static final float BASE_CAM_X = 0.0f;
    private static final float BASE_CAM_Y = -0.95f;
    private static final float BASE_CAM_Z = -8.2f;
    private static final float BASE_CAM_PITCH = 16.0f;
    private static final float BASE_CAM_YAW = 0.0f;

    // fight camera move left (+X) and up (-Y)
    private static final float FIGHT_CAM_X = 0.65f;
    private static final float FIGHT_CAM_Y = -1.28f;
    private static final float FIGHT_CAM_Z = -8.35f;
    private static final float FIGHT_CAM_PITCH = 21.0f;
    private static final float FIGHT_CAM_YAW = -7.0f;
    private static final double FIGHT_CAMERA_TRANSITION_SECONDS = 0.75;

    // terrain floor 
    private static final float TERRAIN_FLOOR_SCALE_XZ = 3.4f;
    private static final float TERRAIN_FLOOR_SCALE_Y = 4.2f;
    private static final float TERRAIN_FLOOR_Y = -1.02f;

    private final FightingGameLWJGL game;
    private final GameUIRenderer ui;
    private double lastFrameTime = -1.0;
    private double fightCameraBlend = 0.0;

    GameRenderer(FightingGameLWJGL game, GameUIRenderer ui) {
        this.game = game;
        this.ui = ui;
    }

    void render() {
        double now = glfwGetTime();
        double deltaSeconds = lastFrameTime < 0.0 ? 0.0 : (now - lastFrameTime);
        lastFrameTime = now;

        // render arena and fighters in 3d
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glDisable(GL_BLEND);

        float aspect = (float) FightingGameLWJGL.WIDTH / FightingGameLWJGL.HEIGHT;
        setPerspective(65.0f, aspect, 0.1f, 100.0f);

        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();
        applyCamera(deltaSeconds);

        drawArena();

        switch (game.gameState) {
            case MODE_SELECT -> renderModeSelectScene();
            case MULTIPLAYER_ROLE_SELECT -> renderMultiplayerRoleScene();
            case MULTIPLAYER_WAITING -> renderMultiplayerWaitingScene();
            case CHAR_SELECT -> renderCharacterSelectScene();
            case FIGHT -> renderFightScene();
        }
    }

    private void applyCamera(double deltaSeconds) {
        // 3/4 camera view transition: blend from the default menu camera into the fight camera angle.
        if (game.gameState == FightingGameLWJGL.GameState.FIGHT) {
            double step = deltaSeconds / FIGHT_CAMERA_TRANSITION_SECONDS;
            fightCameraBlend = Math.min(1.0, fightCameraBlend + step);
        } else {
            fightCameraBlend = 0.0;
        }

        float blend = smoothStep((float) fightCameraBlend);
        float x = lerp(BASE_CAM_X, FIGHT_CAM_X, blend);
        float y = lerp(BASE_CAM_Y, FIGHT_CAM_Y, blend);
        float z = lerp(BASE_CAM_Z, FIGHT_CAM_Z, blend);
        float pitch = lerp(BASE_CAM_PITCH, FIGHT_CAM_PITCH, blend);
        float yaw = lerp(BASE_CAM_YAW, FIGHT_CAM_YAW, blend);

        glTranslatef(x, y, z);
        glRotatef(pitch, 1.0f, 0.0f, 0.0f);
        glRotatef(yaw, 0.0f, 1.0f, 0.0f);
    }

    // camera for menu scene
    private void renderModeSelectScene() {
        float yaw = (float) (glfwGetTime() * 22.0);
        drawFighterPreview(-1.7f, 0.0f, game.p1.selected, false, yaw);
        drawFighterPreview(1.7f, 0.0f, game.p2.selected, false, 180.0f + yaw);
        ui.drawModeSelectOverlay();
    }

    // camera moves for fight
    private void renderMultiplayerRoleScene() {
        float yaw = (float) (glfwGetTime() * 22.0);
        drawFighterPreview(-1.7f, 0.0f, game.p1.selected, false, yaw);
        drawFighterPreview(1.7f, 0.0f, game.p2.selected, false, 180.0f + yaw);
        ui.drawMultiplayerRoleSelectOverlay();
    }

    // camera for waiting before fight
    private void renderMultiplayerWaitingScene() {
        float yaw = (float) (glfwGetTime() * 18.0);
        drawFighterPreview(-1.7f, 0.0f, game.p1.selected, false, yaw);
        drawFighterPreview(1.7f, 0.0f, game.p2.selected, false, 180.0f + yaw);
        ui.drawMultiplayerWaitingOverlay();
    }

    // camera for character select
    private void renderCharacterSelectScene() {
        float yaw = (float) (glfwGetTime() * 35.0);
        drawFighterPreview(-2.1f, 0.0f, game.p1.selected, game.p1.ready, yaw);
        drawFighterPreview(2.1f, 0.0f, game.p2.selected, game.p2.ready, 180.0f + yaw);
        ui.drawCharSelectOverlay();
    }

    // camera for fight
    private void renderFightScene() {
        drawFighterInFight(game.p1, true);
        drawFighterInFight(game.p2, false);
        ui.drawFightOverlay();
    }

    private void drawArena() {
        if (game.terrainFloorMesh != null) {
            drawTerrainFloor();
            drawArenaBoundaryOutline();
            return;
        }

        glDisable(GL_TEXTURE_2D);
        glDisable(GL_CULL_FACE);
        glColor3f(0.10f, 0.13f, 0.20f);
        glBegin(GL_QUADS);
        glVertex3f(-FightingGameLWJGL.ARENA_HALF_WIDTH, -1.00f, -FightingGameLWJGL.ARENA_HALF_DEPTH);
        glVertex3f(FightingGameLWJGL.ARENA_HALF_WIDTH, -1.00f, -FightingGameLWJGL.ARENA_HALF_DEPTH);
        glVertex3f(FightingGameLWJGL.ARENA_HALF_WIDTH, -1.00f, FightingGameLWJGL.ARENA_HALF_DEPTH);
        glVertex3f(-FightingGameLWJGL.ARENA_HALF_WIDTH, -1.00f, FightingGameLWJGL.ARENA_HALF_DEPTH);
        glEnd();

        glColor3f(0.24f, 0.27f, 0.34f);
        glBegin(GL_LINES);
        for (float x = -FightingGameLWJGL.ARENA_HALF_WIDTH; x <= FightingGameLWJGL.ARENA_HALF_WIDTH + 0.001f; x += 0.6f) {
            glVertex3f(x, -0.99f, -FightingGameLWJGL.ARENA_HALF_DEPTH);
            glVertex3f(x, -0.99f, FightingGameLWJGL.ARENA_HALF_DEPTH);
        }
        for (float z = -FightingGameLWJGL.ARENA_HALF_DEPTH; z <= FightingGameLWJGL.ARENA_HALF_DEPTH + 0.001f; z += 0.4f) {
            glVertex3f(-FightingGameLWJGL.ARENA_HALF_WIDTH, -0.99f, z);
            glVertex3f(FightingGameLWJGL.ARENA_HALF_WIDTH, -0.99f, z);
        }
        glEnd();

        drawArenaBoundaryOutline();
    }

    private void drawTerrainFloor() {
        glPushMatrix();
        glTranslatef(0.0f, TERRAIN_FLOOR_Y, 0.0f);
        glScalef(TERRAIN_FLOOR_SCALE_XZ, TERRAIN_FLOOR_SCALE_Y, TERRAIN_FLOOR_SCALE_XZ);
        game.terrainFloorMesh.render(0.30f, 0.36f, 0.30f);
        glPopMatrix();
    }

    private void drawArenaBoundaryOutline() {
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_CULL_FACE);
        glColor3f(0.80f, 0.86f, 0.92f);
        glBegin(GL_LINE_LOOP);
        glVertex3f(-FightingGameLWJGL.ARENA_HALF_WIDTH, -0.98f, -FightingGameLWJGL.ARENA_HALF_DEPTH);
        glVertex3f(FightingGameLWJGL.ARENA_HALF_WIDTH, -0.98f, -FightingGameLWJGL.ARENA_HALF_DEPTH);
        glVertex3f(FightingGameLWJGL.ARENA_HALF_WIDTH, -0.98f, FightingGameLWJGL.ARENA_HALF_DEPTH);
        glVertex3f(-FightingGameLWJGL.ARENA_HALF_WIDTH, -0.98f, FightingGameLWJGL.ARENA_HALF_DEPTH);
        glEnd();
        glEnable(GL_CULL_FACE);
    }

    private void drawFighterPreview(float x, float z, int modelIndex, boolean ready, float yawDegrees) {
        FightingGameLWJGL.FighterModel model = game.models.get(modelIndex);
        float[] color = model.color();

        glPushMatrix();
        glTranslatef(x, -0.15f, z);
        glRotatef(yawDegrees, 0.0f, 1.0f, 0.0f);
        float tint = ready ? 0.18f : 0.0f;
        model.mesh().render(
            clamp(color[0] + tint, 0.0f, 1.0f),
            clamp(color[1] + tint, 0.0f, 1.0f),
            clamp(color[2] + tint, 0.0f, 1.0f)
        );
        glPopMatrix();
    }

    private void drawFighterInFight(FightingGameLWJGL.Fighter fighter, boolean firstPlayer) {
        FightingGameLWJGL.FighterModel model = game.models.get(fighter.selected);
        float[] base = model.color();

        glPushMatrix();
        glTranslatef(fighter.x, -0.15f, fighter.z);
        glRotatef(fighter.facing, 0.0f, 1.0f, 0.0f);

        if (fighter.attack != FightingGameLWJGL.AttackType.NONE) {
            float push = fighter.attack == FightingGameLWJGL.AttackType.PUNCH ? 0.12f : 0.22f;
            float dir = fighter.facing > 0 ? 1.0f : -1.0f;
            glTranslatef(dir * push, 0.0f, 0.0f);
        }

        float boost = fighter.cooldown > 0 ? 0.10f : 0.0f;
        if (firstPlayer) {
            model.mesh().render(
                clamp(base[0] + boost, 0.0f, 1.0f),
                clamp(base[1] + 0.06f, 0.0f, 1.0f),
                clamp(base[2], 0.0f, 1.0f)
            );
        } else {
            model.mesh().render(
                clamp(base[0] + 0.06f, 0.0f, 1.0f),
                clamp(base[1], 0.0f, 1.0f),
                clamp(base[2] + boost, 0.0f, 1.0f)
            );
        }
        glPopMatrix();
    }

    private static void setPerspective(float fovDeg, float aspect, float near, float far) {
        float top = (float) (Math.tan(Math.toRadians(fovDeg) * 0.5) * near);
        float bottom = -top;
        float right = top * aspect;
        float left = -right;

        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        glFrustum(left, right, bottom, top, near, far);
    }

    // HELPER MATH FUNCTIONS

    private static float clamp(float value, float lo, float hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothStep(float t) {
        float clamped = clamp(t, 0.0f, 1.0f);
        return clamped * clamped * (3.0f - 2.0f * clamped);
    }
}
