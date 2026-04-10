import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.stb.STBEasyFont;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public class FightingGameLWJGL {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;

    private static final float ARENA_HALF_WIDTH = 3.2f;
    private static final float ARENA_HALF_DEPTH = 1.6f;
    private static final float MOVE_SPEED = 0.08f;

    private static final int MAX_HEALTH = 100;
    private static final int PUNCH_DAMAGE = 8;
    private static final int KICK_DAMAGE = 14;
    private static final int PUNCH_ACTIVE_FRAMES = 7;
    private static final int KICK_ACTIVE_FRAMES = 10;
    private static final int PUNCH_COOLDOWN = 18;
    private static final int KICK_COOLDOWN = 28;
    private static final float PUNCH_RANGE = 1.45f;
    private static final float KICK_RANGE = 1.75f;
    // GLB->OBJ exports often already bake V orientation for image-top-left data.
    private static final boolean FLIP_V_COORDINATE = false;

    private static final int[] TRACKED_KEYS = {
        GLFW_KEY_ESCAPE,
        GLFW_KEY_A,
        GLFW_KEY_D,
        GLFW_KEY_W,
        GLFW_KEY_S,
        GLFW_KEY_F,
        GLFW_KEY_R,
        GLFW_KEY_LEFT,
        GLFW_KEY_RIGHT,
        GLFW_KEY_UP,
        GLFW_KEY_DOWN,
        GLFW_KEY_PERIOD,
        GLFW_KEY_LEFT_SHIFT,
        GLFW_KEY_RIGHT_SHIFT,
        GLFW_KEY_ENTER
    };

    private long window;
    private final Fighter p1 = new Fighter();
    private final Fighter p2 = new Fighter();
    private final boolean[] prevKeys = new boolean[GLFW_KEY_LAST + 1];

    private List<FighterModel> models;

    private GameState gameState = GameState.CHAR_SELECT;
    private int winner = 0;

    public static void main(String[] args) {
        new FightingGameLWJGL().run();
    }

    private void run() {
        initWindow();
        try {
            models = loadModels();
            if (models.isEmpty()) {
                throw new IllegalStateException("No OBJ files found under final-project/models or models");
            }

            p1.selected = 0;
            p2.selected = models.size() > 1 ? 1 : 0;

            loop();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            for (FighterModel model : models == null ? List.<FighterModel>of() : models) {
                model.mesh().dispose();
            }
            glfwDestroyWindow(window);
            glfwTerminate();
            GLFWErrorCallback cb = glfwSetErrorCallback(null);
            if (cb != null) cb.free();
        }
    }

    private void initWindow() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);

        window = glfwCreateWindow(WIDTH, HEIGHT, "LWJGL 3D Fighter", NULL, NULL);
        if (window == NULL) {
            throw new IllegalStateException("Failed to create GLFW window");
        }

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwShowWindow(window);

        GL.createCapabilities();

        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glClearColor(0.06f, 0.07f, 0.11f, 1.0f);
    }

    private void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            update();
            render();
            glfwSwapBuffers(window);
            snapshotKeys();
        }
    }

    private void update() {
        if (gameState == GameState.CHAR_SELECT) {
            updateCharacterSelect();
        } else {
            updateFight();
        }

        if (isPressed(GLFW_KEY_ESCAPE)) {
            glfwSetWindowShouldClose(window, true);
        }
    }

    private void updateCharacterSelect() {
        if (isPressed(GLFW_KEY_A)) {
            p1.selected = wrap(p1.selected - 1, models.size());
            p1.ready = false;
        }
        if (isPressed(GLFW_KEY_D)) {
            p1.selected = wrap(p1.selected + 1, models.size());
            p1.ready = false;
        }
        if (isPressed(GLFW_KEY_LEFT)) {
            p2.selected = wrap(p2.selected - 1, models.size());
            p2.ready = false;
        }
        if (isPressed(GLFW_KEY_RIGHT)) {
            p2.selected = wrap(p2.selected + 1, models.size());
            p2.ready = false;
        }

        if (isPressed(GLFW_KEY_F)) p1.ready = !p1.ready;
        if (isPressed(GLFW_KEY_RIGHT_SHIFT) || isPressed(GLFW_KEY_LEFT_SHIFT)) p2.ready = !p2.ready;

        if (p1.ready && p2.ready) {
            startRound();
        }

        String title = "CHAR SELECT | P1: " + models.get(p1.selected).name() + (p1.ready ? " [READY]" : " [NOT READY]")
            + " | P2: " + models.get(p2.selected).name() + (p2.ready ? " [READY]" : " [NOT READY]");
        glfwSetWindowTitle(window, title);
    }

    private void startRound() {
        p1.health = MAX_HEALTH;
        p2.health = MAX_HEALTH;

        p1.x = -1.25f;
        p1.z = 0.0f;
        p2.x = 1.25f;
        p2.z = 0.0f;

        p1.facing = 90.0f;
        p2.facing = -90.0f;

        p1.attack = AttackType.NONE;
        p2.attack = AttackType.NONE;
        p1.attackTimer = 0;
        p2.attackTimer = 0;
        p1.cooldown = 0;
        p2.cooldown = 0;
        p1.hitApplied = false;
        p2.hitApplied = false;

        winner = 0;
        gameState = GameState.FIGHT;
    }

    private void updateFight() {
        if (winner != 0) {
            if (isPressed(GLFW_KEY_ENTER)) {
                p1.ready = false;
                p2.ready = false;
                gameState = GameState.CHAR_SELECT;
            }
            glfwSetWindowTitle(window, "FIGHT OVER | Winner: P" + winner + " | Press ENTER to return");
            return;
        }

        float p1dx = 0.0f;
        float p1dz = 0.0f;
        float p2dx = 0.0f;
        float p2dz = 0.0f;

        if (isDown(GLFW_KEY_W)) p1dz -= MOVE_SPEED;
        if (isDown(GLFW_KEY_S)) p1dz += MOVE_SPEED;
        if (isDown(GLFW_KEY_A)) p1dx -= MOVE_SPEED;
        if (isDown(GLFW_KEY_D)) p1dx += MOVE_SPEED;

        if (isDown(GLFW_KEY_UP)) p2dz -= MOVE_SPEED;
        if (isDown(GLFW_KEY_DOWN)) p2dz += MOVE_SPEED;
        if (isDown(GLFW_KEY_LEFT)) p2dx -= MOVE_SPEED;
        if (isDown(GLFW_KEY_RIGHT)) p2dx += MOVE_SPEED;

        p1.x = clamp(p1.x + p1dx, -ARENA_HALF_WIDTH, ARENA_HALF_WIDTH);
        p1.z = clamp(p1.z + p1dz, -ARENA_HALF_DEPTH, ARENA_HALF_DEPTH);
        p2.x = clamp(p2.x + p2dx, -ARENA_HALF_WIDTH, ARENA_HALF_WIDTH);
        p2.z = clamp(p2.z + p2dz, -ARENA_HALF_DEPTH, ARENA_HALF_DEPTH);

        p1.facing = p2.x >= p1.x ? 90.0f : -90.0f;
        p2.facing = p1.x >= p2.x ? 90.0f : -90.0f;

        updateAttackState(p1, isPressed(GLFW_KEY_F), isPressed(GLFW_KEY_R));
        updateAttackState(p2, isPressed(GLFW_KEY_PERIOD), isPressed(GLFW_KEY_RIGHT_SHIFT) || isPressed(GLFW_KEY_LEFT_SHIFT));

        resolveHit(p1, p2);
        resolveHit(p2, p1);

        if (p1.health <= 0) {
            p1.health = 0;
            winner = 2;
        }
        if (p2.health <= 0) {
            p2.health = 0;
            winner = 1;
        }

        String title = "FIGHT | P1 HP " + p1.health + " | P2 HP " + p2.health;
        glfwSetWindowTitle(window, title);
    }

    private void updateAttackState(Fighter fighter, boolean punchPressed, boolean kickPressed) {
        if (fighter.cooldown > 0) fighter.cooldown--;

        if (fighter.attack != AttackType.NONE) {
            fighter.attackTimer--;
            if (fighter.attackTimer <= 0) {
                fighter.attack = AttackType.NONE;
                fighter.hitApplied = false;
            }
            return;
        }

        if (fighter.cooldown > 0) return;

        if (kickPressed) {
            fighter.attack = AttackType.KICK;
            fighter.attackTimer = KICK_ACTIVE_FRAMES;
            fighter.cooldown = KICK_COOLDOWN;
            fighter.hitApplied = false;
            return;
        }

        if (punchPressed) {
            fighter.attack = AttackType.PUNCH;
            fighter.attackTimer = PUNCH_ACTIVE_FRAMES;
            fighter.cooldown = PUNCH_COOLDOWN;
            fighter.hitApplied = false;
        }
    }

    private void resolveHit(Fighter attacker, Fighter defender) {
        if (attacker.attack == AttackType.NONE || attacker.hitApplied) return;

        float dx = defender.x - attacker.x;
        float dz = defender.z - attacker.z;
        float dist = (float) Math.sqrt(dx * dx + dz * dz);

        float range = attacker.attack == AttackType.PUNCH ? PUNCH_RANGE : KICK_RANGE;
        if (dist > range) return;

        boolean inFront = attacker.facing > 0 ? dx > -0.1f : dx < 0.1f;
        if (!inFront) return;

        defender.health -= (attacker.attack == AttackType.PUNCH ? PUNCH_DAMAGE : KICK_DAMAGE);
        attacker.hitApplied = true;
    }

    private void render() {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glDisable(GL_BLEND);

        float aspect = (float) WIDTH / HEIGHT;
        setPerspective(65.0f, aspect, 0.1f, 100.0f);

        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();
        glTranslatef(0.0f, -0.95f, -8.2f);
        glRotatef(16.0f, 1.0f, 0.0f, 0.0f);

        drawArena();

        if (gameState == GameState.CHAR_SELECT) {
            drawFighterPreview(-2.1f, 0.0f, p1.selected, p1.ready, (float) (glfwGetTime() * 35.0));
            drawFighterPreview(2.1f, 0.0f, p2.selected, p2.ready, (float) (180.0 + glfwGetTime() * 35.0));
            drawCharSelectOverlay();
        } else {
            drawFighterInFight(p1, true);
            drawFighterInFight(p2, false);
            drawFightOverlay();
        }
    }

    private void drawArena() {
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_CULL_FACE);
        glColor3f(0.10f, 0.13f, 0.20f);
        glBegin(GL_QUADS);
        glVertex3f(-ARENA_HALF_WIDTH, -1.00f, -ARENA_HALF_DEPTH);
        glVertex3f(ARENA_HALF_WIDTH, -1.00f, -ARENA_HALF_DEPTH);
        glVertex3f(ARENA_HALF_WIDTH, -1.00f, ARENA_HALF_DEPTH);
        glVertex3f(-ARENA_HALF_WIDTH, -1.00f, ARENA_HALF_DEPTH);
        glEnd();

        glColor3f(0.24f, 0.27f, 0.34f);
        glBegin(GL_LINES);
        for (float x = -ARENA_HALF_WIDTH; x <= ARENA_HALF_WIDTH + 0.001f; x += 0.6f) {
            glVertex3f(x, -0.99f, -ARENA_HALF_DEPTH);
            glVertex3f(x, -0.99f, ARENA_HALF_DEPTH);
        }
        for (float z = -ARENA_HALF_DEPTH; z <= ARENA_HALF_DEPTH + 0.001f; z += 0.4f) {
            glVertex3f(-ARENA_HALF_WIDTH, -0.99f, z);
            glVertex3f(ARENA_HALF_WIDTH, -0.99f, z);
        }
        glEnd();

        glColor3f(0.80f, 0.86f, 0.92f);
        glBegin(GL_LINE_LOOP);
        glVertex3f(-ARENA_HALF_WIDTH, -0.98f, -ARENA_HALF_DEPTH);
        glVertex3f(ARENA_HALF_WIDTH, -0.98f, -ARENA_HALF_DEPTH);
        glVertex3f(ARENA_HALF_WIDTH, -0.98f, ARENA_HALF_DEPTH);
        glVertex3f(-ARENA_HALF_WIDTH, -0.98f, ARENA_HALF_DEPTH);
        glEnd();
        glEnable(GL_CULL_FACE);
    }

    private void drawFighterPreview(float x, float z, int modelIndex, boolean ready, float yawDegrees) {
        FighterModel m = models.get(modelIndex);
        float[] color = m.color();

        glPushMatrix();
        glTranslatef(x, -0.15f, z);
        glRotatef(yawDegrees, 0.0f, 1.0f, 0.0f);
        float tint = ready ? 0.18f : 0.0f;
        m.mesh().render(
            clamp(color[0] + tint, 0.0f, 1.0f),
            clamp(color[1] + tint, 0.0f, 1.0f),
            clamp(color[2] + tint, 0.0f, 1.0f)
        );
        glPopMatrix();
    }

    private void drawFighterInFight(Fighter fighter, boolean firstPlayer) {
        FighterModel m = models.get(fighter.selected);
        float[] base = m.color();

        glPushMatrix();
        glTranslatef(fighter.x, -0.15f, fighter.z);
        glRotatef(fighter.facing, 0.0f, 1.0f, 0.0f);

        if (fighter.attack != AttackType.NONE) {
            float push = fighter.attack == AttackType.PUNCH ? 0.12f : 0.22f;
            float dir = fighter.facing > 0 ? 1.0f : -1.0f;
            glTranslatef(dir * push, 0.0f, 0.0f);
        }

        float boost = fighter.cooldown > 0 ? 0.10f : 0.0f;
        if (firstPlayer) {
            m.mesh().render(
                clamp(base[0] + boost, 0.0f, 1.0f),
                clamp(base[1] + 0.06f, 0.0f, 1.0f),
                clamp(base[2], 0.0f, 1.0f)
            );
        } else {
            m.mesh().render(
                clamp(base[0] + 0.06f, 0.0f, 1.0f),
                clamp(base[1], 0.0f, 1.0f),
                clamp(base[2] + boost, 0.0f, 1.0f)
            );
        }
        glPopMatrix();
    }

    private void drawCharSelectOverlay() {
        beginOverlay();

        drawRectPx(20, 20, WIDTH - 40, 48, 0.07f, 0.09f, 0.13f, 0.82f);
        drawText(36, 36, "CHARACTER SELECT - P1 A/D + F READY | P2 LEFT/RIGHT + SHIFT READY", 1.0f, 1.0f, 1.0f);

        drawRectPx(80, 620, 430, 34, p1.ready ? 0.30f : 0.78f, p1.ready ? 0.86f : 0.50f, p1.ready ? 0.48f : 0.36f, 0.95f);
        drawRectPx(WIDTH - 510, 620, 430, 34, p2.ready ? 0.30f : 0.78f, p2.ready ? 0.86f : 0.50f, p2.ready ? 0.48f : 0.36f, 0.95f);

        drawText(92, 643, "P1: " + models.get(p1.selected).name() + (p1.ready ? " READY" : " NOT READY"), 0.08f, 0.08f, 0.08f);
        drawText(WIDTH - 498, 643, "P2: " + models.get(p2.selected).name() + (p2.ready ? " READY" : " NOT READY"), 0.08f, 0.08f, 0.08f);

        endOverlay();
    }

    private void drawFightOverlay() {
        beginOverlay();

        drawRectPx(20, 20, 430, 26, 0.15f, 0.18f, 0.25f, 0.9f);
        drawRectPx(WIDTH - 450, 20, 430, 26, 0.15f, 0.18f, 0.25f, 0.9f);

        drawRectPx(22, 22, (int) (426.0f * (p1.health / (float) MAX_HEALTH)), 22, 0.30f, 0.86f, 0.52f, 0.95f);
        drawRectPx(WIDTH - 448, 22, (int) (426.0f * (p2.health / (float) MAX_HEALTH)), 22, 0.92f, 0.46f, 0.40f, 0.95f);

        drawText(25, 17, "P1 " + models.get(p1.selected).name() + " HP " + p1.health, 1.0f, 1.0f, 1.0f);
        drawText(WIDTH - 445, 17, "P2 " + models.get(p2.selected).name() + " HP " + p2.health, 1.0f, 1.0f, 1.0f);

        drawRectPx(20, HEIGHT - 62, WIDTH - 40, 42, 0.05f, 0.07f, 0.10f, 0.86f);
        drawText(34, HEIGHT - 36, "P1 MOVE WASD | F PUNCH | R KICK     P2 MOVE ARROWS | . PUNCH | SHIFT KICK", 0.93f, 0.95f, 0.99f);

        if (winner != 0) {
            drawRectPx(330, 250, 620, 180, 0.04f, 0.05f, 0.07f, 0.92f);
            drawRectPx(340, 260, 600, 160, winner == 1 ? 0.22f : 0.34f, 0.24f, winner == 2 ? 0.22f : 0.34f, 0.90f);
            drawText(530, 318, "PLAYER " + winner + " WINS", 1.0f, 1.0f, 1.0f);
            drawText(430, 356, "PRESS ENTER TO RETURN TO CHARACTER SELECT", 0.94f, 0.94f, 0.98f);
        }

        endOverlay();
    }

    private void beginOverlay() {
        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glLoadIdentity();
        glOrtho(0.0, WIDTH, HEIGHT, 0.0, -1.0, 1.0);

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

    private void setPerspective(float fovDeg, float aspect, float near, float far) {
        float top = (float) (Math.tan(Math.toRadians(fovDeg) * 0.5) * near);
        float bottom = -top;
        float right = top * aspect;
        float left = -right;

        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        glFrustum(left, right, bottom, top, near, far);
    }

    private boolean isDown(int key) {
        return key >= 0 && key <= GLFW_KEY_LAST && glfwGetKey(window, key) == GLFW_PRESS;
    }

    private boolean isPressed(int key) {
        if (key < 0 || key > GLFW_KEY_LAST) return false;
        boolean now = glfwGetKey(window, key) == GLFW_PRESS;
        return now && !prevKeys[key];
    }

    private void snapshotKeys() {
        for (int key : TRACKED_KEYS) {
            prevKeys[key] = glfwGetKey(window, key) == GLFW_PRESS;
        }
    }

    private int wrap(int value, int size) {
        int m = value % size;
        if (m < 0) m += size;
        return m;
    }

    private float clamp(float value, float lo, float hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    private List<FighterModel> loadModels() throws IOException {
        Path root = resolveModelsRoot();
        List<Path> objFiles;
        try (Stream<Path> stream = Files.walk(root)) {
            objFiles = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".obj"))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }

        List<FighterModel> out = new ArrayList<>();
        for (int i = 0; i < objFiles.size(); i++) {
            Path obj = objFiles.get(i);
            Mesh mesh = ObjLoader.loadAndNormalize(obj);
            String name = obj.getParent() != null ? obj.getParent().getFileName().toString() : obj.getFileName().toString();
            out.add(new FighterModel(name, mesh, paletteColor(i)));
        }
        return out;
    }

    private Path resolveModelsRoot() {
        Path cwdModels = Path.of("models");
        if (Files.exists(cwdModels)) return cwdModels;

        Path nested = Path.of("final-project", "models");
        if (Files.exists(nested)) return nested;

        throw new IllegalStateException("Could not locate models directory");
    }

    private float[] paletteColor(int i) {
        float[][] palette = {
            {0.89f, 0.54f, 0.38f},
            {0.46f, 0.74f, 0.40f},
            {0.40f, 0.67f, 0.84f},
            {0.86f, 0.72f, 0.36f},
            {0.80f, 0.49f, 0.67f},
            {0.48f, 0.82f, 0.70f}
        };
        return palette[i % palette.length];
    }

    private enum GameState {
        CHAR_SELECT,
        FIGHT
    }

    private enum AttackType {
        NONE,
        PUNCH,
        KICK
    }

    private static final class Fighter {
        int selected;
        boolean ready;
        float x;
        float z;
        float facing;
        int health = MAX_HEALTH;

        AttackType attack = AttackType.NONE;
        int attackTimer;
        int cooldown;
        boolean hitApplied;
    }

    private record FighterModel(String name, Mesh mesh, float[] color) {}

    private static final class Mesh {
        private final List<MeshPart> parts;

        private Mesh(List<MeshPart> parts) {
            this.parts = parts;
        }

        void render(float tintR, float tintG, float tintB) {
            // Imported OBJ files may not share consistent winding, so avoid dropping faces.
            glDisable(GL_CULL_FACE);
            for (MeshPart part : parts) {
                if (part.textureId != 0) {
                    glEnable(GL_TEXTURE_2D);
                    glBindTexture(GL_TEXTURE_2D, part.textureId);
                    // Treat alpha textures as cutouts so transparent texels don't draw as black quads.
                    glEnable(GL_ALPHA_TEST);
                    glAlphaFunc(GL_GREATER, 0.1f);
                    // Keep texture colors faithful; tinting textured parts causes odd-looking materials.
                    glColor3f(1.0f, 1.0f, 1.0f);
                } else {
                    glDisable(GL_TEXTURE_2D);
                    glDisable(GL_ALPHA_TEST);
                    glColor3f(tintR, tintG, tintB);
                }

                glBegin(GL_TRIANGLES);
                for (int i = 0; i < part.vertices.length; i += 5) {
                    glTexCoord2f(part.vertices[i + 3], part.vertices[i + 4]);
                    glVertex3f(part.vertices[i], part.vertices[i + 1], part.vertices[i + 2]);
                }
                glEnd();
            }
            glDisable(GL_TEXTURE_2D);
            glDisable(GL_ALPHA_TEST);
            glBindTexture(GL_TEXTURE_2D, 0);
            glEnable(GL_CULL_FACE);
        }

        void dispose() {
            for (MeshPart part : parts) {
                if (part.textureId != 0) {
                    glDeleteTextures(part.textureId);
                }
            }
        }
    }

    private record MeshPart(int textureId, float[] vertices) {}

    private static final class ObjLoader {
        private static Mesh loadAndNormalize(Path filePath) throws IOException {
            Path objDir = filePath.toAbsolutePath().normalize().getParent();
            List<float[]> rawVertices = new ArrayList<>();
            List<float[]> rawUvs = new ArrayList<>();

            List<TriangleRef> triangles = new ArrayList<>();
            Map<String, MaterialInfo> materials = new HashMap<>();
            Map<Path, Integer> textureCache = new HashMap<>();

            String currentMaterial = null;

            try (BufferedReader reader = Files.newBufferedReader(filePath)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;

                    String[] t = line.split("\\s+");
                    if ("v".equals(t[0]) && t.length >= 4) {
                        rawVertices.add(new float[] {
                            Float.parseFloat(t[1]),
                            Float.parseFloat(t[2]),
                            Float.parseFloat(t[3])
                        });
                    } else if ("vt".equals(t[0]) && t.length >= 3) {
                        rawUvs.add(new float[] {
                            Float.parseFloat(t[1]),
                            Float.parseFloat(t[2])
                        });
                    } else if ("mtllib".equals(t[0]) && t.length >= 2 && objDir != null) {
                        Path mtlPath = objDir.resolve(t[1]).normalize();
                        if (Files.exists(mtlPath)) {
                            parseMtl(mtlPath, objDir, materials, textureCache);
                        }
                    } else if ("usemtl".equals(t[0]) && t.length >= 2) {
                        currentMaterial = t[1];
                    } else if ("f".equals(t[0]) && t.length >= 4) {
                        VertexRef[] refs = new VertexRef[t.length - 1];
                        for (int i = 1; i < t.length; i++) {
                            refs[i - 1] = parseVertexRef(t[i], rawVertices.size(), rawUvs.size());
                        }

                        int textureId = 0;
                        if (currentMaterial != null) {
                            MaterialInfo info = materials.get(currentMaterial);
                            if (info != null) {
                                textureId = info.textureId;
                            }
                        }

                        for (int i = 1; i < refs.length - 1; i++) {
                            triangles.add(new TriangleRef(textureId, refs[0], refs[i], refs[i + 1]));
                        }
                    }
                }
            }

            if (rawVertices.isEmpty() || triangles.isEmpty()) {
                throw new IllegalStateException("OBJ has no renderable geometry: " + filePath);
            }

            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            float maxZ = Float.NEGATIVE_INFINITY;
            for (float[] v : rawVertices) {
                minX = Math.min(minX, v[0]);
                minY = Math.min(minY, v[1]);
                minZ = Math.min(minZ, v[2]);
                maxX = Math.max(maxX, v[0]);
                maxY = Math.max(maxY, v[1]);
                maxZ = Math.max(maxZ, v[2]);
            }

            float cx = (minX + maxX) * 0.5f;
            float cy = (minY + maxY) * 0.5f;
            float cz = (minZ + maxZ) * 0.5f;
            float ex = maxX - minX;
            float ey = maxY - minY;
            float ez = maxZ - minZ;
            float maxExtent = Math.max(ex, Math.max(ey, ez));
            if (maxExtent < 1e-6f) maxExtent = 1.0f;
            float scale = 1.9f / maxExtent;

            Map<Integer, ArrayList<Float>> buckets = new HashMap<>();
            for (TriangleRef tri : triangles) {
                ArrayList<Float> data = buckets.computeIfAbsent(tri.textureId, k -> new ArrayList<>());
                appendVertex(data, tri.a, rawVertices, rawUvs, cx, cy, cz, scale);
                appendVertex(data, tri.b, rawVertices, rawUvs, cx, cy, cz, scale);
                appendVertex(data, tri.c, rawVertices, rawUvs, cx, cy, cz, scale);
            }

            List<MeshPart> parts = new ArrayList<>();
            for (Map.Entry<Integer, ArrayList<Float>> e : buckets.entrySet()) {
                float[] packed = new float[e.getValue().size()];
                for (int i = 0; i < packed.length; i++) {
                    packed[i] = e.getValue().get(i);
                }
                parts.add(new MeshPart(e.getKey(), packed));
            }

            return new Mesh(parts);
        }

        private static void appendVertex(
            ArrayList<Float> data,
            VertexRef ref,
            List<float[]> rawVertices,
            List<float[]> rawUvs,
            float cx,
            float cy,
            float cz,
            float scale
        ) {
            float[] v = rawVertices.get(ref.vertexIndex);
            float u = 0.0f;
            float vv = 0.0f;
            if (ref.uvIndex >= 0 && ref.uvIndex < rawUvs.size()) {
                float[] uv = rawUvs.get(ref.uvIndex);
                u = uv[0];
                vv = FLIP_V_COORDINATE ? (1.0f - uv[1]) : uv[1];
            }

            data.add((v[0] - cx) * scale);
            data.add((v[1] - cy) * scale);
            data.add((v[2] - cz) * scale);
            data.add(u);
            data.add(vv);
        }

        private static VertexRef parseVertexRef(String token, int vertexCount, int uvCount) {
            String[] fd = token.split("/");
            int vi = Integer.parseInt(fd[0]);
            vi = resolveObjIndex(vi, vertexCount);

            int ti = -1;
            if (fd.length >= 2 && !fd[1].isBlank()) {
                ti = Integer.parseInt(fd[1]);
                ti = resolveObjIndex(ti, uvCount);
            }
            return new VertexRef(vi, ti);
        }

        private static int resolveObjIndex(int idx, int count) {
            if (idx > 0) return idx - 1;
            if (idx < 0) return count + idx;
            throw new IllegalArgumentException("OBJ index cannot be 0");
        }

        private static void parseMtl(
            Path mtlPath,
            Path objDir,
            Map<String, MaterialInfo> materials,
            Map<Path, Integer> textureCache
        ) throws IOException {
            String currentName = null;
            MaterialInfo current = null;

            try (BufferedReader reader = Files.newBufferedReader(mtlPath)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] t = line.split("\\s+");

                    if ("newmtl".equals(t[0]) && t.length >= 2) {
                        currentName = t[1];
                        current = new MaterialInfo();
                        materials.put(currentName, current);
                    } else if ("map_Kd".equals(t[0]) && t.length >= 2 && current != null && objDir != null) {
                        StringBuilder texName = new StringBuilder(t[1]);
                        for (int i = 2; i < t.length; i++) {
                            texName.append(' ').append(t[i]);
                        }
                        Path texPath = objDir.resolve(texName.toString()).normalize();
                        if (Files.exists(texPath)) {
                            int textureId = textureCache.computeIfAbsent(texPath, ObjLoader::loadTextureSafe);
                            current.textureId = textureId;
                        }
                    }
                }
            }
        }

        private static int loadTextureSafe(Path path) {
            try {
                return loadTexture(path);
            } catch (IOException e) {
                return 0;
            }
        }

        private static int loadTexture(Path path) throws IOException {
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) {
                throw new IOException("Failed to decode image: " + path);
            }

            int w = image.getWidth();
            int h = image.getHeight();
            int[] argb = new int[w * h];
            image.getRGB(0, 0, w, h, argb, 0, w);

            ByteBuffer rgba = BufferUtils.createByteBuffer(w * h * 4);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = argb[y * w + x];
                    rgba.put((byte) ((p >> 16) & 0xFF));
                    rgba.put((byte) ((p >> 8) & 0xFF));
                    rgba.put((byte) (p & 0xFF));
                    rgba.put((byte) ((p >> 24) & 0xFF));
                }
            }
            rgba.flip();

            int tex = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, tex);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
            glBindTexture(GL_TEXTURE_2D, 0);
            return tex;
        }
    }

    private static final class MaterialInfo {
        int textureId = 0;
    }

    private record VertexRef(int vertexIndex, int uvIndex) {}

    private record TriangleRef(int textureId, VertexRef a, VertexRef b, VertexRef c) {}
}
