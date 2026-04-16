import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.stb.STBEasyFont;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;

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
    private final Map<String, Integer> portraitTextures = new HashMap<>();

    private List<FighterModel> models;
    private NetworkMode networkMode = NetworkMode.OFFLINE;
    private HostSession hostSession;
    private ClientSession clientSession;
    private String networkStatus = "LOCAL";
    private GameMode gameMode = GameMode.MULTIPLAYER;
    private int modeSelectionIndex = 0;
    private int multiplayerRoleIndex = 0;
    private int aiDecisionTimer = 0;
    private int aiAttackCooldown = 0;
    private float aiStrafeBias = 0.0f;
    private String joinHost = "127.0.0.1";
    private int joinPort = 7777;
    private final SoundEngine sounds = new SoundEngine();

    private GameState gameState = GameState.MODE_SELECT;
    private int winner = 0;

    public static void main(String[] args) {
        new FightingGameLWJGL().run(args);
    }

    private void run(String[] args) {
        configureNetwork(args == null ? new String[0] : args);
        initWindow();
        try {
            models = loadModels();
            if (models.isEmpty()) {
                throw new IllegalStateException("No OBJ files found under final-project/models or models");
            }
            loadPortraitTextures();

            p1.selected = 0;
            p2.selected = models.size() > 1 ? 1 : 0;
            p1.ready = false;
            p2.ready = false;

            if (networkMode != NetworkMode.OFFLINE) {
                gameMode = GameMode.MULTIPLAYER;
                gameState = GameState.MULTIPLAYER_WAITING;
                if (networkMode == NetworkMode.HOST) {
                    networkStatus = "HOST " + hostSession.port + " | waiting for player to connect";
                } else if (networkMode == NetworkMode.CLIENT) {
                    networkStatus = "JOIN " + joinHost + ":" + joinPort + " | connecting";
                }
            } else {
                gameState = GameState.MODE_SELECT;
            }

            startNetworkIfNeeded();
            loop();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            closeNetwork();
            sounds.shutdown();
            for (FighterModel model : models == null ? List.<FighterModel>of() : models) {
                model.mesh().dispose();
            }
            disposePortraitTextures();
            glfwDestroyWindow(window);
            glfwTerminate();
            GLFWErrorCallback cb = glfwSetErrorCallback(null);
            if (cb != null) cb.free();
        }
    }

    private void configureNetwork(String[] args) {
        if (args.length == 0) {
            networkMode = NetworkMode.OFFLINE;
            return;
        }

        if ("--host".equalsIgnoreCase(args[0])) {
            networkMode = NetworkMode.HOST;
            int port = args.length >= 2 ? Integer.parseInt(args[1]) : 7777;
            joinPort = port;
            hostSession = new HostSession(port);
            networkStatus = "HOST " + port;
            return;
        }

        if ("--join".equalsIgnoreCase(args[0])) {
            if (args.length < 2) {
                throw new IllegalArgumentException("Usage: --join <host:port> (example: --join 127.0.0.1:7777)");
            }
            networkMode = NetworkMode.CLIENT;
            String[] hp = args[1].split(":", 2);
            if (hp.length != 2) {
                throw new IllegalArgumentException("Join target must be <host:port>");
            }
            String host = hp[0];
            int port = Integer.parseInt(hp[1]);
            joinHost = host;
            joinPort = port;
            clientSession = new ClientSession(host, port);
            networkStatus = "JOIN " + host + ":" + port;
            return;
        }

        throw new IllegalArgumentException("Unknown args. Use no args, --host [port], or --join host:port");
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
        if (networkMode == NetworkMode.CLIENT) {
            updateClientOnly();
        } else {
            PlayerInput p1Input = collectP1Input();
            PlayerInput p2Input;
            if (networkMode == NetworkMode.HOST) {
                p2Input = hostSession.consumeP2Input();
            } else if (gameMode == GameMode.SINGLE_PLAYER && gameState == GameState.FIGHT) {
                p2Input = buildAiInput();
            } else if (gameMode == GameMode.SINGLE_PLAYER) {
                p2Input = PlayerInput.empty();
            } else {
                p2Input = collectP2Input();
            }

            if (gameState == GameState.MODE_SELECT) {
                updateModeSelect(p1Input);
            } else if (gameState == GameState.MULTIPLAYER_ROLE_SELECT) {
                updateMultiplayerRoleSelect(p1Input);
            } else if (gameState == GameState.MULTIPLAYER_WAITING) {
                updateMultiplayerWaiting();
            } else if (gameState == GameState.CHAR_SELECT) {
                updateCharacterSelectWithInput(p1Input, p2Input);
            } else {
                updateFightWithInput(p1Input, p2Input);
            }

            if (networkMode == NetworkMode.HOST) {
                if (!hostSession.hasActivePlayerTwo()) {
                    if (gameState != GameState.MULTIPLAYER_WAITING) {
                        p1.ready = false;
                        p2.ready = false;
                        winner = 0;
                        gameState = GameState.MULTIPLAYER_WAITING;
                    }
                }
                hostSession.broadcastSnapshot(GameSnapshot.capture(gameState, winner, p1, p2));
                if (hostSession.hasActivePlayerTwo()) {
                    networkStatus = "HOST " + hostSession.port + " | player connected";
                } else {
                    networkStatus = "HOST " + hostSession.port + " | waiting for player to connect";
                }
            }
        }

        if (isPressed(GLFW_KEY_ESCAPE)) {
            glfwSetWindowShouldClose(window, true);
        }
    }

    private void updateModeSelect(PlayerInput p1Input) {
        if (p1Input.selectLeft || p1Input.selectRight) {
            modeSelectionIndex = 1 - modeSelectionIndex;
            sounds.play(Sfx.UI_MOVE);
        }
        if (p1Input.confirm || p1Input.readyToggle) {
            gameMode = modeSelectionIndex == 0 ? GameMode.SINGLE_PLAYER : GameMode.MULTIPLAYER;
            sounds.play(Sfx.UI_CONFIRM);
            p1.ready = false;
            p2.ready = false;
            p2.selected = models.size() > 1 ? wrap(p1.selected + 1, models.size()) : p1.selected;
            if (gameMode == GameMode.SINGLE_PLAYER) {
                networkMode = NetworkMode.OFFLINE;
                networkStatus = "LOCAL";
                gameState = GameState.CHAR_SELECT;
            } else {
                networkMode = NetworkMode.OFFLINE;
                networkStatus = "MULTIPLAYER";
                multiplayerRoleIndex = 0;
                gameState = GameState.MULTIPLAYER_ROLE_SELECT;
            }
        }
        glfwSetWindowTitle(window, "MODE SELECT | " + (modeSelectionIndex == 0 ? "SINGLE PLAYER" : "MULTIPLAYER"));
    }

    private void updateMultiplayerRoleSelect(PlayerInput p1Input) {
        if (p1Input.selectLeft || p1Input.selectRight) {
            multiplayerRoleIndex = 1 - multiplayerRoleIndex;
            sounds.play(Sfx.UI_MOVE);
        }
        if (!(p1Input.confirm || p1Input.readyToggle)) {
            glfwSetWindowTitle(window, "MULTIPLAYER | Select Host or Join");
            return;
        }
        sounds.play(Sfx.UI_CONFIRM);

        p1.ready = false;
        p2.ready = false;
        winner = 0;
        closeNetwork();
        hostSession = null;
        clientSession = null;

        if (multiplayerRoleIndex == 0) {
            try {
                networkMode = NetworkMode.HOST;
                hostSession = new HostSession(joinPort);
                hostSession.start();
                networkStatus = "HOST " + joinPort + " | waiting for player to connect";
                gameState = GameState.MULTIPLAYER_WAITING;
            } catch (IOException e) {
                networkMode = NetworkMode.OFFLINE;
                networkStatus = "HOST START FAILED";
            }
        } else {
            try {
                networkMode = NetworkMode.CLIENT;
                clientSession = new ClientSession(joinHost, joinPort);
                clientSession.connect();
                networkStatus = "JOIN " + joinHost + ":" + joinPort + " | connecting";
                gameState = GameState.MULTIPLAYER_WAITING;
            } catch (IOException e) {
                networkMode = NetworkMode.OFFLINE;
                networkStatus = "JOIN FAILED";
            }
        }
    }

    private void updateMultiplayerWaiting() {
        if (networkMode == NetworkMode.HOST && hostSession != null && hostSession.hasActivePlayerTwo()) {
            p1.ready = false;
            p2.ready = false;
            gameState = GameState.CHAR_SELECT;
        }
        if (networkMode == NetworkMode.OFFLINE) {
            gameState = GameState.MULTIPLAYER_ROLE_SELECT;
        }
    }

    private void updateCharacterSelectWithInput(PlayerInput p1Input, PlayerInput p2Input) {
        if (p1Input.selectLeft) {
            p1.selected = wrap(p1.selected - 1, models.size());
            p1.ready = false;
            sounds.play(Sfx.UI_MOVE);
        }
        if (p1Input.selectRight) {
            p1.selected = wrap(p1.selected + 1, models.size());
            p1.ready = false;
            sounds.play(Sfx.UI_MOVE);
        }
        if (gameMode == GameMode.MULTIPLAYER && p2Input.selectLeft) {
            p2.selected = wrap(p2.selected - 1, models.size());
            p2.ready = false;
            sounds.play(Sfx.UI_MOVE);
        }
        if (gameMode == GameMode.MULTIPLAYER && p2Input.selectRight) {
            p2.selected = wrap(p2.selected + 1, models.size());
            p2.ready = false;
            sounds.play(Sfx.UI_MOVE);
        }

        if (p1Input.readyToggle) {
            p1.ready = !p1.ready;
            sounds.play(Sfx.UI_READY);
        }
        if (gameMode == GameMode.MULTIPLAYER && p2Input.readyToggle) {
            p2.ready = !p2.ready;
            sounds.play(Sfx.UI_READY);
        }
        if (gameMode == GameMode.SINGLE_PLAYER) p2.ready = true;

        if (p1.ready && p2.ready) {
            startRound();
        }

        String p2Name = gameMode == GameMode.SINGLE_PLAYER
            ? models.get(p2.selected).name() + " [AI]"
            : models.get(p2.selected).name() + (p2.ready ? " [READY]" : " [NOT READY]");

        String title = "CHAR SELECT | P1: " + models.get(p1.selected).name() + (p1.ready ? " [READY]" : " [NOT READY]")
            + " | P2: " + p2Name
            + " | " + networkStatus;
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
        aiDecisionTimer = 0;
        aiAttackCooldown = 0;
        aiStrafeBias = 0.0f;

        winner = 0;
        gameState = GameState.FIGHT;
        sounds.play(Sfx.ROUND_START);
    }

    private void updateFightWithInput(PlayerInput p1Input, PlayerInput p2Input) {
        if (winner != 0) {
            if (p1Input.confirm || p2Input.confirm) {
                p1.ready = false;
                p2.ready = gameMode == GameMode.SINGLE_PLAYER;
                gameState = GameState.CHAR_SELECT;
                sounds.play(Sfx.UI_CONFIRM);
            }
            glfwSetWindowTitle(window, "FIGHT OVER | Winner: P" + winner + " | Press ENTER to return | " + networkStatus);
            return;
        }

        float p1dx = 0.0f;
        float p1dz = 0.0f;
        float p2dx = 0.0f;
        float p2dz = 0.0f;

        if (p1Input.up) p1dz -= MOVE_SPEED;
        if (p1Input.down) p1dz += MOVE_SPEED;
        if (p1Input.left) p1dx -= MOVE_SPEED;
        if (p1Input.right) p1dx += MOVE_SPEED;

        if (p2Input.up) p2dz -= MOVE_SPEED;
        if (p2Input.down) p2dz += MOVE_SPEED;
        if (p2Input.left) p2dx -= MOVE_SPEED;
        if (p2Input.right) p2dx += MOVE_SPEED;

        p1.x = clamp(p1.x + p1dx, -ARENA_HALF_WIDTH, ARENA_HALF_WIDTH);
        p1.z = clamp(p1.z + p1dz, -ARENA_HALF_DEPTH, ARENA_HALF_DEPTH);
        p2.x = clamp(p2.x + p2dx, -ARENA_HALF_WIDTH, ARENA_HALF_WIDTH);
        p2.z = clamp(p2.z + p2dz, -ARENA_HALF_DEPTH, ARENA_HALF_DEPTH);

        p1.facing = p2.x >= p1.x ? 90.0f : -90.0f;
        p2.facing = p1.x >= p2.x ? 90.0f : -90.0f;

        updateAttackState(p1, p1Input.punchPressed, p1Input.kickPressed);
        updateAttackState(p2, p2Input.punchPressed, p2Input.kickPressed);

        resolveHit(p1, p2);
        resolveHit(p2, p1);

        if (p1.health <= 0) {
            p1.health = 0;
            if (winner == 0) sounds.play(Sfx.KO);
            winner = 2;
        }
        if (p2.health <= 0) {
            p2.health = 0;
            if (winner == 0) sounds.play(Sfx.KO);
            winner = 1;
        }

        String title = "FIGHT | P1 HP " + p1.health + " | P2 HP " + p2.health + " | " + networkStatus;
        glfwSetWindowTitle(window, title);
    }

    private void updateClientOnly() {
        PlayerInput local = collectClientInput();
        clientSession.sendInput(local);
        GameSnapshot snapshot = clientSession.consumeSnapshot();
        if (snapshot != null) {
            snapshot.applyTo(this);
        }
        networkStatus = clientSession.statusText();
        String title = "NETWORK CLIENT | " + networkStatus;
        glfwSetWindowTitle(window, title);
    }

    private void startNetworkIfNeeded() throws IOException {
        if (networkMode == NetworkMode.HOST && hostSession != null) {
            hostSession.start();
        } else if (networkMode == NetworkMode.CLIENT && clientSession != null) {
            clientSession.connect();
        }
    }

    private void closeNetwork() {
        if (hostSession != null) {
            hostSession.close();
        }
        if (clientSession != null) {
            clientSession.close();
        }
    }

    private PlayerInput collectP1Input() {
        boolean leftPressed = isPressed(GLFW_KEY_A) || isPressed(GLFW_KEY_LEFT);
        boolean rightPressed = isPressed(GLFW_KEY_D) || isPressed(GLFW_KEY_RIGHT);
        return PlayerInput.of(
            isDown(GLFW_KEY_A),
            isDown(GLFW_KEY_D),
            isDown(GLFW_KEY_W),
            isDown(GLFW_KEY_S),
            isPressed(GLFW_KEY_F),
            isPressed(GLFW_KEY_R),
            isPressed(GLFW_KEY_F),
            leftPressed,
            rightPressed,
            isPressed(GLFW_KEY_ENTER)
        );
    }

    private PlayerInput collectP2Input() {
        return PlayerInput.of(
            isDown(GLFW_KEY_LEFT),
            isDown(GLFW_KEY_RIGHT),
            isDown(GLFW_KEY_UP),
            isDown(GLFW_KEY_DOWN),
            isPressed(GLFW_KEY_PERIOD),
            isPressed(GLFW_KEY_RIGHT_SHIFT) || isPressed(GLFW_KEY_LEFT_SHIFT),
            isPressed(GLFW_KEY_RIGHT_SHIFT) || isPressed(GLFW_KEY_LEFT_SHIFT),
            isPressed(GLFW_KEY_LEFT),
            isPressed(GLFW_KEY_RIGHT),
            isPressed(GLFW_KEY_ENTER)
        );
    }

    private PlayerInput collectClientInput() {
        int slot = clientSession.getAssignedSlot();
        if (slot == 1) {
            return collectP1Input();
        }
        if (slot == 2) {
            return collectP2Input();
        }
        return PlayerInput.empty();
    }

    private PlayerInput buildAiInput() {
        if (aiAttackCooldown > 0) aiAttackCooldown--;
        if (aiDecisionTimer > 0) aiDecisionTimer--;
        if (aiDecisionTimer <= 0) {
            aiDecisionTimer = 16;
            aiStrafeBias = (float) Math.sin(glfwGetTime() * 1.7) * 0.55f;
        }

        float dx = p1.x - p2.x;
        float dz = p1.z - p2.z;
        float dist = (float) Math.sqrt(dx * dx + dz * dz);

        boolean moveLeft = false;
        boolean moveRight = false;
        boolean moveUp = false;
        boolean moveDown = false;

        if (Math.abs(dx) > 0.18f) {
            if (dx < 0) moveLeft = true;
            if (dx > 0) moveRight = true;
        }
        if (dist > 1.35f) {
            if (dz < -0.10f) moveUp = true;
            if (dz > 0.10f) moveDown = true;
        } else if (dist < 0.95f) {
            if (dz < 0) moveDown = true;
            if (dz > 0) moveUp = true;
        } else {
            if (aiStrafeBias < -0.15f) moveUp = true;
            if (aiStrafeBias > 0.15f) moveDown = true;
        }

        boolean punch = false;
        boolean kick = false;
        if (aiAttackCooldown == 0 && dist < 1.72f) {
            if (dist > 1.25f) {
                kick = true;
            } else {
                double t = glfwGetTime();
                punch = (Math.sin(t * 2.3) > -0.15);
                kick = !punch;
            }
            aiAttackCooldown = kick ? 28 : 20;
        }

        return PlayerInput.of(
            moveLeft,
            moveRight,
            moveUp,
            moveDown,
            punch,
            kick,
            false,
            false,
            false,
            false
        );
    }

    private void updateAttackState(Fighter fighter, boolean punchPressed, boolean kickPressed) {
        // Play punch SFX immediately on key press for responsive feedback.
        if (punchPressed) {
            sounds.play(Sfx.PUNCH);
        }

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
            sounds.play(Sfx.KICK);
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
        sounds.play(Sfx.HIT);
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

        if (gameState == GameState.MODE_SELECT) {
            drawFighterPreview(-1.7f, 0.0f, p1.selected, false, (float) (glfwGetTime() * 22.0));
            drawFighterPreview(1.7f, 0.0f, p2.selected, false, (float) (180.0 + glfwGetTime() * 22.0));
            drawModeSelectOverlay();
        } else if (gameState == GameState.MULTIPLAYER_ROLE_SELECT) {
            drawFighterPreview(-1.7f, 0.0f, p1.selected, false, (float) (glfwGetTime() * 22.0));
            drawFighterPreview(1.7f, 0.0f, p2.selected, false, (float) (180.0 + glfwGetTime() * 22.0));
            drawMultiplayerRoleSelectOverlay();
        } else if (gameState == GameState.MULTIPLAYER_WAITING) {
            drawFighterPreview(-1.7f, 0.0f, p1.selected, false, (float) (glfwGetTime() * 18.0));
            drawFighterPreview(1.7f, 0.0f, p2.selected, false, (float) (180.0 + glfwGetTime() * 18.0));
            drawMultiplayerWaitingOverlay();
        } else if (gameState == GameState.CHAR_SELECT) {
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
        if (gameMode == GameMode.SINGLE_PLAYER) {
            drawText(36, 36, "CHARACTER SELECT - SINGLE PLAYER | P1 A/D + F READY | P2 IS AI", 1.0f, 1.0f, 1.0f);
        } else {
            drawText(36, 36, "CHARACTER SELECT - MULTIPLAYER | P1 A/D + F READY | P2 LEFT/RIGHT + SHIFT READY", 1.0f, 1.0f, 1.0f);
        }
        drawText(36, 56, "SESSION: " + networkStatus, 0.78f, 0.86f, 0.97f);

        drawRectPx(80, 620, 430, 34, p1.ready ? 0.30f : 0.78f, p1.ready ? 0.86f : 0.50f, p1.ready ? 0.48f : 0.36f, 0.95f);
        drawRectPx(WIDTH - 510, 620, 430, 34, p2.ready ? 0.30f : 0.78f, p2.ready ? 0.86f : 0.50f, p2.ready ? 0.48f : 0.36f, 0.95f);

        drawText(92, 643, "P1: " + models.get(p1.selected).name() + (p1.ready ? " READY" : " NOT READY"), 0.08f, 0.08f, 0.08f);
        String p2Label = gameMode == GameMode.SINGLE_PLAYER
            ? "P2(AI): " + models.get(p2.selected).name() + " READY"
            : "P2: " + models.get(p2.selected).name() + (p2.ready ? " READY" : " NOT READY");
        drawText(WIDTH - 498, 643, p2Label, 0.08f, 0.08f, 0.08f);

        drawCharacterPortraitPlaceholders();

        endOverlay();
    }

    private void drawCharacterPortraitPlaceholders() {
        int tileSize = 92;
        int tileGap = 12;
        int rowY = HEIGHT - 158;
        int totalWidth = models.size() * tileSize + (models.size() - 1) * tileGap;
        int startX = (WIDTH - totalWidth) / 2;

        drawRectPx(startX - 16, rowY - 34, totalWidth + 32, tileSize + 48, 0.05f, 0.07f, 0.10f, 0.84f);
        drawText(startX - 2, rowY - 16, "2D PORTRAIT PLACEHOLDERS", 0.92f, 0.94f, 0.98f);

        for (int i = 0; i < models.size(); i++) {
            int x = startX + i * (tileSize + tileGap);
            boolean p1Selected = i == p1.selected;
            boolean p2Selected = i == p2.selected;
            String modelName = models.get(i).name();
            int portraitTex = portraitTextures.getOrDefault(normalizePortraitKey(modelName), 0);

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

    private void drawFightOverlay() {
        beginOverlay();

        drawRectPx(20, 20, 430, 26, 0.15f, 0.18f, 0.25f, 0.9f);
        drawRectPx(WIDTH - 450, 20, 430, 26, 0.15f, 0.18f, 0.25f, 0.9f);

        drawRectPx(22, 22, (int) (426.0f * (p1.health / (float) MAX_HEALTH)), 22, 0.30f, 0.86f, 0.52f, 0.95f);
        drawRectPx(WIDTH - 448, 22, (int) (426.0f * (p2.health / (float) MAX_HEALTH)), 22, 0.92f, 0.46f, 0.40f, 0.95f);

        drawText(25, 17, "P1 " + models.get(p1.selected).name() + " HP " + p1.health, 1.0f, 1.0f, 1.0f);
        drawText(WIDTH - 445, 17, "P2 " + models.get(p2.selected).name() + " HP " + p2.health, 1.0f, 1.0f, 1.0f);

        drawRectPx(20, HEIGHT - 62, WIDTH - 40, 42, 0.05f, 0.07f, 0.10f, 0.86f);
        if (gameMode == GameMode.SINGLE_PLAYER) {
            drawText(34, HEIGHT - 36, "P1 MOVE WASD | F PUNCH | R KICK     P2 AI CONTROLLED", 0.93f, 0.95f, 0.99f);
        } else {
            drawText(34, HEIGHT - 36, "P1 MOVE WASD | F PUNCH | R KICK     P2 MOVE ARROWS | . PUNCH | SHIFT KICK", 0.93f, 0.95f, 0.99f);
        }
        drawText(34, HEIGHT - 52, "SESSION: " + networkStatus, 0.78f, 0.86f, 0.97f);

        if (winner != 0) {
            drawRectPx(330, 250, 620, 180, 0.04f, 0.05f, 0.07f, 0.92f);
            drawRectPx(340, 260, 600, 160, winner == 1 ? 0.22f : 0.34f, 0.24f, winner == 2 ? 0.22f : 0.34f, 0.90f);
            drawText(530, 318, "PLAYER " + winner + " WINS", 1.0f, 1.0f, 1.0f);
            drawText(430, 356, "PRESS ENTER TO RETURN TO CHARACTER SELECT", 0.94f, 0.94f, 0.98f);
        }

        endOverlay();
    }

    private void drawModeSelectOverlay() {
        beginOverlay();

        drawRectPx(140, 90, WIDTH - 280, 110, 0.05f, 0.07f, 0.10f, 0.90f);
        drawText(370, 140, "SELECT GAME MODE", 0.96f, 0.98f, 1.0f);
        drawText(210, 172, "A/D OR LEFT/RIGHT TO SWITCH  |  ENTER OR F TO CONFIRM", 0.82f, 0.90f, 0.98f);

        int y = 270;
        int boxW = 460;
        int boxH = 110;
        int leftX = 150;
        int rightX = WIDTH - leftX - boxW;

        boolean singleSelected = modeSelectionIndex == 0;
        boolean multiSelected = modeSelectionIndex == 1;

        drawRectPx(leftX, y, boxW, boxH, singleSelected ? 0.18f : 0.08f, singleSelected ? 0.62f : 0.14f, singleSelected ? 0.36f : 0.20f, 0.95f);
        drawRectPx(rightX, y, boxW, boxH, multiSelected ? 0.19f : 0.08f, multiSelected ? 0.40f : 0.14f, multiSelected ? 0.66f : 0.20f, 0.95f);

        if (singleSelected) {
            drawRectOutlinePx(leftX - 4, y - 4, boxW + 8, boxH + 8, 3, 0.46f, 0.93f, 0.66f, 0.98f);
        }
        if (multiSelected) {
            drawRectOutlinePx(rightX - 4, y - 4, boxW + 8, boxH + 8, 3, 0.54f, 0.78f, 0.98f, 0.98f);
        }

        drawText(leftX + 122, y + 50, "SINGLE PLAYER", 0.95f, 1.0f, 0.97f);
        drawText(leftX + 48, y + 82, "LOCAL HUMAN VS AI-CONTROLLED OPPONENT", 0.83f, 0.92f, 0.88f);

        drawText(rightX + 130, y + 50, "MULTIPLAYER", 0.95f, 0.98f, 1.0f);
        drawText(rightX + 54, y + 82, "LOCAL 2P OR NETWORK HOST/JOIN SESSION", 0.84f, 0.90f, 0.97f);

        drawRectPx(180, HEIGHT - 120, WIDTH - 360, 60, 0.06f, 0.08f, 0.12f, 0.88f);
        drawText(208, HEIGHT - 84, "CURRENT SESSION: " + networkStatus, 0.79f, 0.87f, 0.97f);

        endOverlay();
    }

    private void drawMultiplayerRoleSelectOverlay() {
        beginOverlay();

        drawRectPx(140, 90, WIDTH - 280, 110, 0.05f, 0.07f, 0.10f, 0.90f);
        drawText(280, 140, "MULTIPLAYER - SELECT HOST OR JOIN", 0.96f, 0.98f, 1.0f);
        drawText(220, 172, "A/D OR LEFT/RIGHT TO SWITCH  |  ENTER OR F TO CONFIRM", 0.82f, 0.90f, 0.98f);

        int y = 270;
        int boxW = 460;
        int boxH = 120;
        int leftX = 150;
        int rightX = WIDTH - leftX - boxW;
        boolean hostSelected = multiplayerRoleIndex == 0;
        boolean joinSelected = multiplayerRoleIndex == 1;

        drawRectPx(leftX, y, boxW, boxH, hostSelected ? 0.20f : 0.08f, hostSelected ? 0.43f : 0.14f, hostSelected ? 0.64f : 0.20f, 0.95f);
        drawRectPx(rightX, y, boxW, boxH, joinSelected ? 0.21f : 0.08f, joinSelected ? 0.60f : 0.14f, joinSelected ? 0.34f : 0.20f, 0.95f);

        if (hostSelected) drawRectOutlinePx(leftX - 4, y - 4, boxW + 8, boxH + 8, 3, 0.60f, 0.82f, 1.0f, 0.98f);
        if (joinSelected) drawRectOutlinePx(rightX - 4, y - 4, boxW + 8, boxH + 8, 3, 0.58f, 0.98f, 0.76f, 0.98f);

        drawText(leftX + 190, y + 50, "HOST GAME", 0.95f, 0.98f, 1.0f);
        drawText(leftX + 72, y + 82, "CREATE LOBBY AND WAIT FOR PLAYER 2", 0.84f, 0.90f, 0.98f);
        drawText(leftX + 120, y + 106, "PORT: " + joinPort, 0.80f, 0.87f, 0.96f);

        drawText(rightX + 198, y + 50, "JOIN GAME", 0.95f, 1.0f, 0.96f);
        drawText(rightX + 84, y + 82, "CONNECT TO HOST AND BECOME PLAYER 2", 0.86f, 0.94f, 0.88f);
        drawText(rightX + 86, y + 106, "TARGET: " + joinHost + ":" + joinPort, 0.82f, 0.92f, 0.86f);

        drawRectPx(180, HEIGHT - 120, WIDTH - 360, 60, 0.06f, 0.08f, 0.12f, 0.88f);
        drawText(208, HEIGHT - 84, "SESSION: " + networkStatus, 0.79f, 0.87f, 0.97f);

        endOverlay();
    }

    private void drawMultiplayerWaitingOverlay() {
        beginOverlay();

        drawRectPx(220, 170, WIDTH - 440, 300, 0.04f, 0.06f, 0.10f, 0.92f);
        drawRectPx(240, 190, WIDTH - 480, 260, 0.10f, 0.14f, 0.22f, 0.90f);

        if (networkMode == NetworkMode.HOST) {
            drawText(340, 268, "WAITING FOR PLAYER TO CONNECT", 0.94f, 0.97f, 1.0f);
            drawText(352, 305, "SHARE THIS ADDRESS WITH PLAYER 2", 0.84f, 0.91f, 0.98f);
            drawText(396, 344, "HOST PORT: " + joinPort, 0.82f, 0.90f, 0.98f);
        } else if (networkMode == NetworkMode.CLIENT) {
            drawText(380, 268, "JOINING HOST...", 0.94f, 1.0f, 0.94f);
            drawText(326, 305, "WAITING FOR HOST TO UNLOCK CHARACTER SELECT", 0.84f, 0.95f, 0.86f);
            drawText(332, 344, "TARGET: " + joinHost + ":" + joinPort, 0.82f, 0.93f, 0.85f);
        } else {
            drawText(370, 286, "MULTIPLAYER NOT STARTED", 0.96f, 0.95f, 1.0f);
        }

        drawText(274, 414, "STATUS: " + networkStatus, 0.82f, 0.89f, 0.98f);
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

    private void loadPortraitTextures() {
        disposePortraitTextures();
        Path root = resolvePortraitsRoot();
        if (!Files.isDirectory(root)) return;

        try (Stream<Path> stream = Files.list(root)) {
            stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png"))
                .forEach(path -> {
                    int textureId = loadPngTextureSafe(path);
                    if (textureId != 0) {
                        portraitTextures.put(normalizePortraitKey(stripExtension(path.getFileName().toString())), textureId);
                    }
                });
        } catch (IOException ignored) {
        }
    }

    private Path resolvePortraitsRoot() {
        Path cwd = Path.of("assets", "portraits");
        if (Files.isDirectory(cwd)) return cwd;

        Path nested = Path.of("final-project", "assets", "portraits");
        if (Files.isDirectory(nested)) return nested;

        return cwd;
    }

    private void disposePortraitTextures() {
        for (int textureId : portraitTextures.values()) {
            if (textureId != 0) {
                glDeleteTextures(textureId);
            }
        }
        portraitTextures.clear();
    }

    private static String normalizePortraitKey(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    private static int loadPngTextureSafe(Path path) {
        try {
            return loadPngTexture(path);
        } catch (IOException e) {
            return 0;
        }
    }

    private static int loadPngTexture(Path path) throws IOException {
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
        MODE_SELECT,
        MULTIPLAYER_ROLE_SELECT,
        MULTIPLAYER_WAITING,
        CHAR_SELECT,
        FIGHT
    }

    private enum GameMode {
        SINGLE_PLAYER,
        MULTIPLAYER
    }

    private enum Sfx {
        UI_MOVE("menu-screen.wav", 760.0f, 45, 0.20f, 70),
        UI_CONFIRM("menu-screen.wav", 620.0f, 95, 0.24f, 90),
        UI_READY("menu-screen.wav", 910.0f, 75, 0.22f, 110),
        ROUND_START("round_start.wav", 520.0f, 140, 0.26f, 300),
        PUNCH("punch.wav", 270.0f, 70, 0.20f, 80),
        KICK("kick.wav", 200.0f, 95, 0.24f, 120),
        HIT("hit.wav", 140.0f, 110, 0.28f, 95),
        KO("ko.wav", 96.0f, 280, 0.30f, 500);

        final String fileName;
        final float frequency;
        final int durationMs;
        final float volume;
        final int minGapMs;

        Sfx(String fileName, float frequency, int durationMs, float volume, int minGapMs) {
            this.fileName = fileName;
            this.frequency = frequency;
            this.durationMs = durationMs;
            this.volume = volume;
            this.minGapMs = minGapMs;
        }
    }

    private static final class SoundEngine {
        private static final float SAMPLE_RATE = 22050.0f;
        private static final boolean AUDIO_DEBUG = Boolean.parseBoolean(System.getProperty("fg.audio.debug", "true"));
        private final AtomicBoolean enabled = new AtomicBoolean(true);
        private final EnumMap<Sfx, byte[]> wavCache = new EnumMap<>(Sfx.class);
        private final EnumMap<Sfx, byte[]> pcmCache = new EnumMap<>(Sfx.class);
        private final EnumMap<Sfx, Long> lastQueuedAtNs = new EnumMap<>(Sfx.class);
        private final BlockingQueue<Sfx> queue = new LinkedBlockingQueue<>();
        private final List<Clip> activeClips = new CopyOnWriteArrayList<>();
        private final Thread worker;

        private SoundEngine() {
            Path sfxRoot = resolveSfxRoot();
            log("Audio backend: Java Sound (Clip). OpenAL device/context is not used by this build.");
            log("SFX root resolved to: " + sfxRoot.toAbsolutePath());
            for (Sfx sfx : Sfx.values()) {
                byte[] wav = tryLoadWav(sfxRoot, sfx.fileName);
                if (wav != null) {
                    wavCache.put(sfx, wav);
                    log("Loaded WAV bytes for " + sfx.name() + " from " + sfx.fileName + " (" + wav.length + " bytes)");
                } else {
                    log("No WAV found for " + sfx.name() + " at " + sfxRoot.resolve(sfx.fileName).toAbsolutePath() + " (using synth fallback)");
                }
                pcmCache.put(sfx, synthTone(sfx.frequency, sfx.durationMs, sfx.volume));
                lastQueuedAtNs.put(sfx, 0L);
            }
            worker = new Thread(this::runWorker, "fg-sfx-worker");
            worker.setDaemon(true);
            worker.start();
        }

        void play(Sfx sfx) {
            if (!enabled.get()) return;
            long now = System.nanoTime();
            long last = lastQueuedAtNs.getOrDefault(sfx, 0L);
            long minGapNs = sfx.minGapMs * 1_000_000L;
            if (now - last < minGapNs) return;
            lastQueuedAtNs.put(sfx, now);
            queue.offer(sfx);
        }

        void shutdown() {
            enabled.set(false);
            worker.interrupt();
            for (Clip clip : activeClips) {
                try {
                    clip.stop();
                    clip.close();
                } catch (Exception ignored) {
                }
            }
            activeClips.clear();
        }

        private void runWorker() {
            while (enabled.get()) {
                try {
                    Sfx sfx = queue.take();
                    playInternal(sfx);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }

        private void playInternal(Sfx sfx) {
            byte[] wav = wavCache.get(sfx);
            if (wav != null) {
                if (playWavBytes(wav)) return;
            }

            byte[] pcm = pcmCache.get(sfx);
            if (pcm != null) {
                playPcmBytes(pcm);
            }
        }

        private boolean playWavBytes(byte[] wavBytes) {
            try (
                ByteArrayInputStream bais = new ByteArrayInputStream(wavBytes);
                AudioInputStream sourceAis = AudioSystem.getAudioInputStream(bais)
            ) {
                AudioFormat src = sourceAis.getFormat();
                AudioFormat decodedFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    src.getSampleRate(),
                    16,
                    src.getChannels(),
                    src.getChannels() * 2,
                    src.getSampleRate(),
                    false
                );

                DataLine.Info info = new DataLine.Info(Clip.class, decodedFormat);
                if (!AudioSystem.isLineSupported(info)) {
                    log("Decoded WAV line not supported: " + decodedFormat);
                    return false;
                }

                AudioInputStream decodedAis = AudioSystem.getAudioInputStream(decodedFormat, sourceAis);
                Clip clip = AudioSystem.getClip();
                clip.open(decodedAis);
                startClip(clip);
                decodedAis.close();
                return true;
            } catch (Exception e) {
                log("WAV playback failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return false;
            }
        }

        private void playPcmBytes(byte[] pcm) {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            try (
                ByteArrayInputStream bais = new ByteArrayInputStream(pcm);
                AudioInputStream ais = new AudioInputStream(bais, format, pcm.length / 2)
            ) {
                Clip clip = AudioSystem.getClip();
                clip.open(ais);
                startClip(clip);
            } catch (Exception e) {
                log("PCM fallback playback failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        private static Path resolveSfxRoot() {
            Path cwd = Path.of("assets", "sfx");
            if (Files.isDirectory(cwd)) return cwd;
            Path nested = Path.of("final-project", "assets", "sfx");
            if (Files.isDirectory(nested)) return nested;
            return cwd;
        }

        private static byte[] tryLoadWav(Path root, String fileName) {
            Path file = root.resolve(fileName);
            if (!Files.isRegularFile(file)) return null;
            try {
                return Files.readAllBytes(file);
            } catch (IOException ignored) {
                return null;
            }
        }

        private byte[] synthTone(float frequency, int durationMs, float volume) {
            int sampleCount = Math.max(1, (int) (SAMPLE_RATE * durationMs / 1000.0f));
            byte[] out = new byte[sampleCount * 2];
            for (int i = 0; i < sampleCount; i++) {
                float t = i / SAMPLE_RATE;
                float env = envelope(i, sampleCount);
                float sample = (float) Math.sin(2.0 * Math.PI * frequency * t) * volume * env;
                short pcm = (short) (sample * Short.MAX_VALUE);
                out[i * 2] = (byte) (pcm & 0xFF);
                out[i * 2 + 1] = (byte) ((pcm >> 8) & 0xFF);
            }
            return out;
        }

        private float envelope(int i, int total) {
            int attack = Math.max(1, total / 15);
            int release = Math.max(1, total / 8);
            if (i < attack) return i / (float) attack;
            if (i > total - release) return Math.max(0.0f, (total - i) / (float) release);
            return 1.0f;
        }

        private void startClip(Clip clip) {
            activeClips.add(clip);
            clip.addLineListener(event -> {
                LineEvent.Type type = event.getType();
                if (type == LineEvent.Type.STOP || type == LineEvent.Type.CLOSE) {
                    try {
                        clip.close();
                    } catch (Exception ignored) {
                    }
                    activeClips.remove(clip);
                }
            });
            clip.start();
        }

        private static void log(String msg) {
            if (!AUDIO_DEBUG) return;
            System.out.println("[audio] " + msg);
        }
    }

    private enum NetworkMode {
        OFFLINE,
        HOST,
        CLIENT
    }

    private enum AttackType {
        NONE,
        PUNCH,
        KICK
    }

    private static final class PlayerInput {
        final boolean left;
        final boolean right;
        final boolean up;
        final boolean down;
        final boolean punchPressed;
        final boolean kickPressed;
        final boolean readyToggle;
        final boolean selectLeft;
        final boolean selectRight;
        final boolean confirm;

        private PlayerInput(
            boolean left,
            boolean right,
            boolean up,
            boolean down,
            boolean punchPressed,
            boolean kickPressed,
            boolean readyToggle,
            boolean selectLeft,
            boolean selectRight,
            boolean confirm
        ) {
            this.left = left;
            this.right = right;
            this.up = up;
            this.down = down;
            this.punchPressed = punchPressed;
            this.kickPressed = kickPressed;
            this.readyToggle = readyToggle;
            this.selectLeft = selectLeft;
            this.selectRight = selectRight;
            this.confirm = confirm;
        }

        static PlayerInput of(
            boolean left,
            boolean right,
            boolean up,
            boolean down,
            boolean punchPressed,
            boolean kickPressed,
            boolean readyToggle,
            boolean selectLeft,
            boolean selectRight,
            boolean confirm
        ) {
            return new PlayerInput(left, right, up, down, punchPressed, kickPressed, readyToggle, selectLeft, selectRight, confirm);
        }

        static PlayerInput empty() {
            return new PlayerInput(false, false, false, false, false, false, false, false, false, false);
        }

        String toWire() {
            return bool(left) + "|" + bool(right) + "|" + bool(up) + "|" + bool(down) + "|"
                + bool(punchPressed) + "|" + bool(kickPressed) + "|" + bool(readyToggle) + "|"
                + bool(selectLeft) + "|" + bool(selectRight) + "|" + bool(confirm);
        }

        static PlayerInput fromWire(String[] parts, int offset) {
            return new PlayerInput(
                parseBool(parts[offset]),
                parseBool(parts[offset + 1]),
                parseBool(parts[offset + 2]),
                parseBool(parts[offset + 3]),
                parseBool(parts[offset + 4]),
                parseBool(parts[offset + 5]),
                parseBool(parts[offset + 6]),
                parseBool(parts[offset + 7]),
                parseBool(parts[offset + 8]),
                parseBool(parts[offset + 9])
            );
        }

        private static String bool(boolean b) {
            return b ? "1" : "0";
        }
    }

    private record FighterSnapshot(
        int selected,
        boolean ready,
        float x,
        float z,
        float facing,
        int health,
        int attackOrdinal,
        int attackTimer,
        int cooldown
    ) {
        static FighterSnapshot of(Fighter f) {
            return new FighterSnapshot(
                f.selected, f.ready, f.x, f.z, f.facing, f.health,
                f.attack.ordinal(), f.attackTimer, f.cooldown
            );
        }

        void applyTo(Fighter f) {
            f.selected = selected;
            f.ready = ready;
            f.x = x;
            f.z = z;
            f.facing = facing;
            f.health = health;
            f.attack = AttackType.values()[Math.max(0, Math.min(AttackType.values().length - 1, attackOrdinal))];
            f.attackTimer = attackTimer;
            f.cooldown = cooldown;
            f.hitApplied = false;
        }

        String toWire() {
            return selected + "|" + (ready ? 1 : 0) + "|" + x + "|" + z + "|" + facing + "|" + health + "|"
                + attackOrdinal + "|" + attackTimer + "|" + cooldown;
        }

        static FighterSnapshot fromWire(String[] parts, int offset) {
            return new FighterSnapshot(
                Integer.parseInt(parts[offset]),
                parseBool(parts[offset + 1]),
                Float.parseFloat(parts[offset + 2]),
                Float.parseFloat(parts[offset + 3]),
                Float.parseFloat(parts[offset + 4]),
                Integer.parseInt(parts[offset + 5]),
                Integer.parseInt(parts[offset + 6]),
                Integer.parseInt(parts[offset + 7]),
                Integer.parseInt(parts[offset + 8])
            );
        }
    }

    private record GameSnapshot(int stateOrdinal, int winner, FighterSnapshot p1, FighterSnapshot p2) {
        static GameSnapshot capture(GameState state, int winner, Fighter p1, Fighter p2) {
            return new GameSnapshot(state.ordinal(), winner, FighterSnapshot.of(p1), FighterSnapshot.of(p2));
        }

        void applyTo(FightingGameLWJGL game) {
            game.gameState = GameState.values()[Math.max(0, Math.min(GameState.values().length - 1, stateOrdinal))];
            game.winner = winner;
            p1.applyTo(game.p1);
            p2.applyTo(game.p2);
        }

        String toWire(int slot) {
            return "SNAP|" + slot + "|" + stateOrdinal + "|" + winner + "|" + p1.toWire() + "|" + p2.toWire();
        }

        static GameSnapshot fromWire(String[] parts) {
            int state = Integer.parseInt(parts[2]);
            int winner = Integer.parseInt(parts[3]);
            FighterSnapshot p1 = FighterSnapshot.fromWire(parts, 4);
            FighterSnapshot p2 = FighterSnapshot.fromWire(parts, 13);
            return new GameSnapshot(state, winner, p1, p2);
        }
    }

    private static final class HostSession {
        private final int port;
        private final CopyOnWriteArrayList<ClientPeer> peers = new CopyOnWriteArrayList<>();
        private final AtomicReference<PlayerInput> latestP2Input = new AtomicReference<>(PlayerInput.empty());
        private final AtomicBoolean running = new AtomicBoolean(false);
        private ServerSocket serverSocket;
        private Thread acceptThread;

        private HostSession(int port) {
            this.port = port;
        }

        void start() throws IOException {
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress(port));
            running.set(true);
            acceptThread = new Thread(this::acceptLoop, "fg-host-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        private void acceptLoop() {
            while (running.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    socket.setTcpNoDelay(true);
                    int slot = hasSlot2Peer() ? 0 : 2;
                    ClientPeer peer = new ClientPeer(socket, slot);
                    peers.add(peer);
                    peer.start();
                } catch (IOException e) {
                    if (running.get()) {
                        // ignore and continue accepting when possible
                    }
                }
            }
        }

        private boolean hasSlot2Peer() {
            for (ClientPeer peer : peers) {
                if (peer.slot == 2 && peer.connected.get()) return true;
            }
            return false;
        }

        PlayerInput consumeP2Input() {
            return latestP2Input.getAndSet(PlayerInput.empty());
        }

        void broadcastSnapshot(GameSnapshot snapshot) {
            for (ClientPeer peer : peers) {
                if (peer.connected.get()) {
                    peer.send(snapshot.toWire(peer.slot));
                }
            }
        }

        int getClientCount() {
            int count = 0;
            for (ClientPeer peer : peers) {
                if (peer.connected.get()) count++;
            }
            return count;
        }

        boolean hasActivePlayerTwo() {
            for (ClientPeer peer : peers) {
                if (peer.slot == 2 && peer.connected.get()) {
                    return true;
                }
            }
            return false;
        }

        void close() {
            running.set(false);
            if (acceptThread != null) {
                acceptThread.interrupt();
            }
            for (ClientPeer peer : peers) {
                peer.close();
            }
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                }
            }
        }

        private final class ClientPeer {
            private final Socket socket;
            private final int slot;
            private final AtomicBoolean connected = new AtomicBoolean(true);
            private final AtomicReference<PrintWriter> out = new AtomicReference<>();
            private Thread readThread;

            private ClientPeer(Socket socket, int slot) {
                this.socket = socket;
                this.slot = slot;
            }

            void start() throws IOException {
                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                out.set(writer);
                writer.println("WELCOME|" + slot);

                readThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String[] parts = line.split("\\|");
                            if (parts.length < 11 || !"INPUT".equals(parts[0])) continue;
                            if (slot == 2) {
                                latestP2Input.set(PlayerInput.fromWire(parts, 1));
                            }
                        }
                    } catch (IOException ignored) {
                    } finally {
                        close();
                    }
                }, "fg-host-peer-read-" + slot);
                readThread.setDaemon(true);
                readThread.start();
            }

            void send(String line) {
                PrintWriter writer = out.get();
                if (writer != null) writer.println(line);
            }

            void close() {
                connected.set(false);
                out.set(null);
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static final class ClientSession {
        private final String host;
        private final int port;
        private final AtomicReference<GameSnapshot> latestSnapshot = new AtomicReference<>();
        private final AtomicBoolean connected = new AtomicBoolean(false);
        private volatile int assignedSlot = 2;
        private Socket socket;
        private PrintWriter writer;
        private Thread readThread;

        private ClientSession(String host, int port) {
            this.host = host;
            this.port = port;
        }

        void connect() throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setTcpNoDelay(true);
            writer = new PrintWriter(socket.getOutputStream(), true);
            connected.set(true);

            readThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.split("\\|");
                        if (parts.length < 2) continue;
                        if ("WELCOME".equals(parts[0])) {
                            assignedSlot = Integer.parseInt(parts[1]);
                        } else if ("SNAP".equals(parts[0]) && parts.length >= 22) {
                            assignedSlot = Integer.parseInt(parts[1]);
                            latestSnapshot.set(GameSnapshot.fromWire(parts));
                        }
                    }
                } catch (IOException ignored) {
                } finally {
                    connected.set(false);
                }
            }, "fg-client-read");
            readThread.setDaemon(true);
            readThread.start();
        }

        int getAssignedSlot() {
            return assignedSlot;
        }

        void sendInput(PlayerInput input) {
            if (!connected.get() || writer == null) return;
            writer.println("INPUT|" + input.toWire());
        }

        GameSnapshot consumeSnapshot() {
            return latestSnapshot.getAndSet(null);
        }

        String statusText() {
            if (!connected.get()) {
                return "DISCONNECTED " + host + ":" + port;
            }
            if (assignedSlot == 0) {
                return "CONNECTED AS SPECTATOR";
            }
            return "CONNECTED AS P" + assignedSlot;
        }

        void close() {
            connected.set(false);
            if (readThread != null) {
                readThread.interrupt();
            }
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static boolean parseBool(String raw) {
        return "1".equals(raw) || "true".equalsIgnoreCase(raw);
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
