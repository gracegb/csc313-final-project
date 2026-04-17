import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

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
import java.util.EnumSet;
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

import java.util.Deque;
import java.util.ArrayDeque;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public class FightingGameLWJGL {
    static final int WIDTH = 1280;
    static final int HEIGHT = 720;

    static final float ARENA_HALF_WIDTH = 3.2f;
    static final float ARENA_HALF_DEPTH = 1.6f;
    private static final float MOVE_SPEED = 0.08f;
    private static final float FIGHTER_MIN_X_GAP = 0.08f;
    private static final float HIT_KNOCKBACK = 0.18f;

    static final int MAX_HEALTH = 100;
    private static final int PUNCH_DAMAGE = 8;
    private static final int KICK_DAMAGE = 14;
    private static final int PUNCH_ACTIVE_FRAMES = 7;
    private static final int KICK_ACTIVE_FRAMES = 10;
    private static final int PUNCH_COOLDOWN = 18;
    private static final int KICK_COOLDOWN = 28;
    private static final int ATTACK_RECOVERY_FRAMES = 10;
    private static final int ATTACK_FATIGUE_MAX = 18;
    private static final int ATTACK_FATIGUE_STEP = 4;
    private static final int STUN_FRAMES = 90; // 1.5s
    private static final int BLOCK_WINDOW_FRAMES = 6;
    private static final int BLOCK_COOLDOWN_FRAMES = 18;
    private static final float PUNCH_RANGE = 1.45f;
    private static final float KICK_RANGE = 1.75f;
    private static final int AI_DECISION_FRAMES = 24;
    private static final float AI_APPROACH_DISTANCE = 1.55f;
    private static final float AI_RETREAT_DISTANCE = 1.10f;
    private static final float AI_ATTACK_MIN_DISTANCE = 1.02f;
    private static final float AI_ATTACK_MAX_DISTANCE = 1.62f;
    private static final float AI_ATTACK_ALIGNMENT_Z = 0.32f;
    private static final int AI_ATTACK_INTERVAL_MIN_FRAMES = 60;  // ~1s
    private static final int AI_ATTACK_INTERVAL_MAX_FRAMES = 180; // ~3s
    private static final int AI_RETREAT_MIN_FRAMES = 36;
    private static final int AI_RETREAT_MAX_FRAMES = 72;
    private static final boolean FLIP_V_COORDINATE = false;

    private static final int[] TRACKED_KEYS = {
        GLFW_KEY_ESCAPE,
        GLFW_KEY_A,
        GLFW_KEY_D,
        GLFW_KEY_W,
        GLFW_KEY_S,
        GLFW_KEY_F,
        GLFW_KEY_R,
        GLFW_KEY_G,
        GLFW_KEY_LEFT,
        GLFW_KEY_RIGHT,
        GLFW_KEY_UP,
        GLFW_KEY_DOWN,
        GLFW_KEY_PERIOD,
        GLFW_KEY_K,
        GLFW_KEY_LEFT_SHIFT,
        GLFW_KEY_RIGHT_SHIFT,
        GLFW_KEY_ENTER,
        GLFW_KEY_B
    };

    private long window;
    final Fighter p1 = new Fighter();
    final Fighter p2 = new Fighter();
    private final boolean[] prevKeys = new boolean[GLFW_KEY_LAST + 1];
    final Map<String, Integer> portraitTextures = new HashMap<>();

    List<FighterModel> models;
    Mesh terrainFloorMesh;
    NetworkMode networkMode = NetworkMode.OFFLINE;
    private HostSession hostSession;
    private ClientSession clientSession;
    String networkStatus = "LOCAL";
    GameMode gameMode = GameMode.MULTIPLAYER;
    int modeSelectionIndex = 0;
    int multiplayerRoleIndex = 0;
    private int aiDecisionTimer = 0;
    private int aiAttackCooldown = 0;
    private int aiRetreatTimer = 0;
    private float aiStrafeBias = 0.0f;
    String joinHost = "127.0.0.1";
    int joinPort = 7777;
    private boolean audioEnabled = true;
    private final SoundEngine sounds = new SoundEngine();
    private final GameUIRenderer uiRenderer = new GameUIRenderer(this);
    private final GameRenderer renderer = new GameRenderer(this, uiRenderer);

    private final Deque<GameState> stateStack = new ArrayDeque<>();
    GameState gameState = GameState.MODE_SELECT;
    int winner = 0;

    public static void main(String[] args) {
        new FightingGameLWJGL().run(args);
    }

    private void run(String[] args) {
        configureNetwork(args == null ? new String[0] : args);
        if (!audioEnabled) {
            sounds.setEnabled(false);
        }
        initWindow();
        try {
            models = loadModels();
            if (models.isEmpty()) {
                throw new IllegalStateException("No OBJ files found under final-project/models or models");
            }
            terrainFloorMesh = loadTerrainFloorMesh();
            loadPortraitTextures();

            p1.selected = 0;
            p2.selected = models.size() > 1 ? (int)(Math.random() * models.size()) : 0;
            p1.ready = false;
            p2.ready = false;

            if (networkMode != NetworkMode.OFFLINE) {
                gameMode = GameMode.MULTIPLAYER;
                changeState(GameState.MULTIPLAYER_WAITING);
                if (networkMode == NetworkMode.HOST) {
                    networkStatus = "HOST " + hostSession.port + " | waiting for player to connect";
                } else if (networkMode == NetworkMode.CLIENT) {
                    networkStatus = "JOIN " + joinHost + ":" + joinPort + " | connecting";
                }
            } else {
                changeState(GameState.MODE_SELECT);
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
            if (terrainFloorMesh != null) {
                terrainFloorMesh.dispose();
            }
            disposePortraitTextures();
            glfwDestroyWindow(window);
            glfwTerminate();
            GLFWErrorCallback cb = glfwSetErrorCallback(null);
            if (cb != null) cb.free();
        }
    }

    private void configureNetwork(String[] args) {
        List<String> positional = new ArrayList<>();
        for (String arg : args) {
            if ("--no-audio".equalsIgnoreCase(arg)) {
                audioEnabled = false;
            } else {
                positional.add(arg);
            }
        }

        if (positional.isEmpty()) {
            networkMode = NetworkMode.OFFLINE;
            return;
        }

        if ("--host".equalsIgnoreCase(positional.get(0))) {
            networkMode = NetworkMode.HOST;
            int port = positional.size() >= 2 ? Integer.parseInt(positional.get(1)) : 7777;
            joinPort = port;
            hostSession = new HostSession(port);
            networkStatus = "HOST " + port;
            return;
        }

        if ("--join".equalsIgnoreCase(positional.get(0))) {
            if (positional.size() < 2) {
                throw new IllegalArgumentException("Usage: --join <host:port> (example: --join 127.0.0.1:7777)");
            }
            networkMode = NetworkMode.CLIENT;
            String[] hp = positional.get(1).split(":", 2);
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

        throw new IllegalArgumentException("Unknown args. Use no args, --host [port], --join host:port, and optional --no-audio");
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
        uiRenderer.init();
    }

    private void loop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            update();
            renderer.render();
            glfwSwapBuffers(window);
            snapshotKeys();
        }
    }

    private void update() {
        sounds.setMusic(trackForState(gameState));
        PlayerInput p1Input = collectP1Input();

        // back button
        if (p1Input.back) {
            goBack();
            return;
        }

        // update based on game state and input
        if (networkMode == NetworkMode.CLIENT) {
            updateClientOnly();
        } else {
            updateHostOrLocal(p1Input);
        }

        // close on escape
        if (isPressed(GLFW_KEY_ESCAPE)) {
            glfwSetWindowShouldClose(window, true);
        }
    }

    private void updateHostOrLocal(PlayerInput p1Input) {
        PlayerInput p2Input = resolveP2Input();
        updateByState(p1Input, p2Input);

        if (networkMode == NetworkMode.HOST) {
            updateHostNetworking();
        }
    }

    private PlayerInput resolveP2Input() {
        if (networkMode == NetworkMode.HOST) {
            return hostSession.consumeP2Input();
        }
        if (gameMode == GameMode.SINGLE_PLAYER && gameState == GameState.FIGHT) {
            return buildAiInput();
        }
        if (gameMode == GameMode.SINGLE_PLAYER) {
            return PlayerInput.empty();
        }
        return collectP2Input();
    }

    private void updateByState(PlayerInput p1Input, PlayerInput p2Input) {
        switch (gameState) {
            case MODE_SELECT -> updateModeSelect(p1Input);
            case MULTIPLAYER_ROLE_SELECT -> updateMultiplayerRoleSelect(p1Input);
            case MULTIPLAYER_WAITING -> updateMultiplayerWaiting();
            case CHAR_SELECT -> updateCharacterSelectWithInput(p1Input, p2Input);
            case FIGHT -> updateFightWithInput(p1Input, p2Input);
        }
    }

    private void updateHostNetworking() {
        if (!hostSession.hasActivePlayerTwo() && gameState != GameState.MULTIPLAYER_WAITING) {
            p1.ready = false;
            p2.ready = false;
            winner = 0;
            changeState(GameState.MULTIPLAYER_WAITING);
        }

        hostSession.broadcastSnapshot(GameSnapshot.capture(gameState, winner, p1, p2));

        if (hostSession.hasActivePlayerTwo()) {
            networkStatus = "HOST " + hostSession.port + " | player connected";
        } else {
            networkStatus = "HOST " + hostSession.port + " | waiting for player to connect";
        }
    }

    private MusicTrack trackForState(GameState state) {
        return state == GameState.FIGHT ? MusicTrack.FIGHT : MusicTrack.MAIN_MENU;
    }

    private void goBack() {
        if (gameState == GameState.FIGHT) return;
        if (gameMode == GameMode.MULTIPLAYER && gameState == GameState.MULTIPLAYER_WAITING) return;
        if (stateStack.isEmpty()) return;
        gameState = stateStack.pop();
    }

    private void setState(GameState newState) {
        changeState(newState);
    }

    private void changeState(GameState newState) {
        stateStack.push(gameState);
        gameState = newState;
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
            p2.selected = models.size() > 1 ? (int)(Math.random() * models.size()) : p1.selected;
            if (gameMode == GameMode.SINGLE_PLAYER) {
                networkMode = NetworkMode.OFFLINE;
                networkStatus = "LOCAL";
                setState(GameState.CHAR_SELECT);
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
                changeState(GameState.MULTIPLAYER_WAITING);
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
                changeState(GameState.MULTIPLAYER_WAITING);
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
            setState(GameState.CHAR_SELECT);
        }
        if (networkMode == NetworkMode.OFFLINE) {
            gameState = GameState.MULTIPLAYER_ROLE_SELECT;
        }
    }

    private void updateCharacterSelectWithInput(PlayerInput p1Input, PlayerInput p2Input) {
        moveSelectionIfNeeded(p1, p1Input.selectLeft, p1Input.selectRight);
        if (gameMode == GameMode.MULTIPLAYER) {
            moveSelectionIfNeeded(p2, p2Input.selectLeft, p2Input.selectRight);
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

    private void moveSelectionIfNeeded(Fighter fighter, boolean moveLeft, boolean moveRight) {
        if (moveLeft) {
            fighter.selected = wrap(fighter.selected - 1, models.size());
            fighter.ready = false;
            sounds.play(Sfx.UI_MOVE);
        }
        if (moveRight) {
            fighter.selected = wrap(fighter.selected + 1, models.size());
            fighter.ready = false;
            sounds.play(Sfx.UI_MOVE);
        }
    }

    private void startRound() {
        resetFighterForRound(p1, -1.25f, 0.0f, 90.0f);
        resetFighterForRound(p2, 1.25f, 0.0f, -90.0f);
        aiDecisionTimer = 0;
        aiAttackCooldown = AI_ATTACK_INTERVAL_MIN_FRAMES
            + (int) (Math.random() * (AI_ATTACK_INTERVAL_MAX_FRAMES - AI_ATTACK_INTERVAL_MIN_FRAMES + 1));
        aiRetreatTimer = 0;
        aiStrafeBias = 0.0f;

        winner = 0;
        changeState(GameState.FIGHT);
        sounds.play(Sfx.ROUND_START);
    }

    private void resetFighterForRound(Fighter fighter, float x, float z, float facing) {
        fighter.health = MAX_HEALTH;
        fighter.x = x;
        fighter.z = z;
        fighter.facing = facing;
        fighter.attack = AttackType.NONE;
        fighter.attackTimer = 0;
        fighter.cooldown = 0;
        fighter.hitApplied = false;
        fighter.attackRecoveryTimer = 0;
        fighter.attackFatigue = 0;
        fighter.stunTimer = 0;
        fighter.blockWindowTimer = 0;
        fighter.blockCooldown = 0;
    }

    // main fight loop
    private void updateFightWithInput(PlayerInput p1Input, PlayerInput p2Input) {
        // listen for fight over and return to menu
        if (winner != 0) {
            updateFightOver(p1Input, p2Input);
            return;
        }

        updateCombatState(p1, p1Input.blockPressed, p1Input.punchPressed, p1Input.kickPressed);
        updateCombatState(p2, p2Input.blockPressed, p2Input.punchPressed, p2Input.kickPressed);

        float prevP1X = p1.x;
        float prevP2X = p2.x;

        moveFighter(p1, p1Input);
        moveFighter(p2, p2Input);
        resolveFighterCollision(prevP1X, prevP2X);

        p1.facing = p2.x >= p1.x ? 90.0f : -90.0f;
        p2.facing = p1.x >= p2.x ? 90.0f : -90.0f;

        resolveHit(p1, p2);
        resolveHit(p2, p1);

        updateWinnerIfNeeded();

        String title = "FIGHT | P1 HP " + p1.health + " | P2 HP " + p2.health + " | " + networkStatus;
        glfwSetWindowTitle(window, title);
    }

    private void updateFightOver(PlayerInput p1Input, PlayerInput p2Input) {
        if (p1Input.confirm || p2Input.confirm) {
            p1.ready = false;
            p2.ready = gameMode == GameMode.SINGLE_PLAYER;
            stateStack.clear();
            stateStack.push(GameState.MODE_SELECT);
            gameState = GameState.CHAR_SELECT;
            sounds.play(Sfx.UI_CONFIRM);
        }
        glfwSetWindowTitle(window, "FIGHT OVER | Winner: P" + winner + " | Press ENTER to return | " + networkStatus);
    }

    // fighter movement
    private void moveFighter(Fighter fighter, PlayerInput input) {
        if (fighter.stunTimer > 0) {
            return;
        }

        float dx = 0.0f;
        float dz = 0.0f;
        if (input.up) dz -= MOVE_SPEED;
        if (input.down) dz += MOVE_SPEED;
        if (input.left) dx -= MOVE_SPEED;
        if (input.right) dx += MOVE_SPEED;

        // clamp movement to arena boundary
        fighter.x = clamp(fighter.x + dx, -ARENA_HALF_WIDTH, ARENA_HALF_WIDTH);
        fighter.z = clamp(fighter.z + dz, -ARENA_HALF_DEPTH, ARENA_HALF_DEPTH);
    }

    private void resolveFighterCollision(float prevP1X, float prevP2X) {
        // so they don't stick together
        float p1Radius = models.get(p1.selected).collisionRadius();
        float p2Radius = models.get(p2.selected).collisionRadius();
        float minDistance = p1Radius + p2Radius;
        float minDistanceSq = minDistance * minDistance;

        for (int i = 0; i < 8; i++) {
            float dx = p2.x - p1.x;
            float dz = p2.z - p1.z;
            float distSq = dx * dx + dz * dz;
            if (distSq >= minDistanceSq) break;

            if (distSq < 1e-6f) {
                dx = 1.0f;
                dz = 0.0f;
                distSq = 1.0f;
            }

            float dist = (float) Math.sqrt(distSq);
            float overlap = minDistance - dist;
            float nx = dx / dist;
            float nz = dz / dist;

            float pushP1 = overlap * 0.5f;
            float pushP2 = overlap - pushP1;

            // help prevent pinning in corner
            float movedP1 = pushFighter(p1, -nx, -nz, pushP1);
            float movedP2 = pushFighter(p2, nx, nz, pushP2);
            float unresolved = overlap - (movedP1 + movedP2);
            if (unresolved > 1e-4f) {
                movedP1 += pushFighter(p1, -nx, -nz, unresolved * 0.5f);
                movedP2 += pushFighter(p2, nx, nz, unresolved * 0.5f);
            }
        }

        if (prevP1X <= prevP2X && p1.x > p2.x - FIGHTER_MIN_X_GAP) {
            float center = (p1.x + p2.x) * 0.5f;
            p1.x = clamp(center - FIGHTER_MIN_X_GAP * 0.5f, -ARENA_HALF_WIDTH, ARENA_HALF_WIDTH);
            p2.x = clamp(center + FIGHTER_MIN_X_GAP * 0.5f, -ARENA_HALF_WIDTH, ARENA_HALF_WIDTH);
        } else if (prevP2X <= prevP1X && p2.x > p1.x - FIGHTER_MIN_X_GAP) {
            float center = (p1.x + p2.x) * 0.5f;
            p2.x = clamp(center - FIGHTER_MIN_X_GAP * 0.5f, -ARENA_HALF_WIDTH, ARENA_HALF_WIDTH);
            p1.x = clamp(center + FIGHTER_MIN_X_GAP * 0.5f, -ARENA_HALF_WIDTH, ARENA_HALF_WIDTH);
        }
    }

    private float pushFighter(Fighter fighter, float dirX, float dirZ, float amount) {
        if (amount <= 0.0f) return 0.0f;
        float startX = fighter.x;
        float startZ = fighter.z;
        fighter.x = clamp(startX + dirX * amount, -ARENA_HALF_WIDTH, ARENA_HALF_WIDTH);
        fighter.z = clamp(startZ + dirZ * amount, -ARENA_HALF_DEPTH, ARENA_HALF_DEPTH);
        float dx = fighter.x - startX;
        float dz = fighter.z - startZ;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    private void updateWinnerIfNeeded() {
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
        // P2P host listens; client connects to that host
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
            isPressed(GLFW_KEY_G),
            isPressed(GLFW_KEY_F),
            leftPressed,
            rightPressed,
            isPressed(GLFW_KEY_ENTER),
            isPressed(GLFW_KEY_B)
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
            isPressed(GLFW_KEY_K),
            isPressed(GLFW_KEY_RIGHT_SHIFT) || isPressed(GLFW_KEY_LEFT_SHIFT),
            isPressed(GLFW_KEY_LEFT),
            isPressed(GLFW_KEY_RIGHT),
            isPressed(GLFW_KEY_ENTER),
            isPressed(GLFW_KEY_B)
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
        if (aiRetreatTimer > 0) aiRetreatTimer--;

        if (p2.attack != AttackType.NONE || p2.stunTimer > 0) {
            return PlayerInput.empty();
        }

        float dx = p1.x - p2.x;
        float dz = p1.z - p2.z;
        float dist = (float) Math.sqrt(dx * dx + dz * dz);

        boolean moveLeft = false;
        boolean moveRight = false;
        boolean moveUp = false;
        boolean moveDown = false;

        // retreat before attacking again
        if (aiAttackCooldown > 0 || aiRetreatTimer > 0) {
            if (dx < -0.08f) moveRight = true;
            if (dx > 0.08f) moveLeft = true;
            if (dz < -0.08f) moveDown = true;
            if (dz > 0.08f) moveUp = true;
        } else {
            if (Math.abs(dx) > 0.12f) {
                if (dx < 0) moveLeft = true;
                if (dx > 0) moveRight = true;
            }
            if (dist > AI_ATTACK_MAX_DISTANCE * 0.94f) {
                if (dz < -0.10f) moveUp = true;
                if (dz > 0.10f) moveDown = true;
            } else {
                if (Math.abs(dz) > 0.05f) {
                    moveUp = dz < 0;
                    moveDown = dz > 0;
                }
            }
            if (dist <= AI_ATTACK_MAX_DISTANCE + 0.20f && Math.abs(dz) > AI_ATTACK_ALIGNMENT_Z * 0.45f) {
                moveUp = dz < 0;
                moveDown = dz > 0;
            }
        }

        boolean punch = false;
        boolean kick = false;
        boolean block = false;
        boolean inAttackBand = dist >= AI_ATTACK_MIN_DISTANCE && dist <= AI_ATTACK_MAX_DISTANCE;
        boolean alignedForAttack = Math.abs(dz) <= AI_ATTACK_ALIGNMENT_Z;
        if (p1.attack != AttackType.NONE && dist < 1.56f && alignedForAttack) {
            block = Math.random() < 0.20;
        }
        boolean canStartAttack =
            p2.cooldown <= 0 &&
            p2.attackRecoveryTimer <= 0 &&
            p2.attack == AttackType.NONE;

        if (aiAttackCooldown <= 0 && canStartAttack && inAttackBand && alignedForAttack) {
            if (dist <= PUNCH_RANGE * 0.96f) {
                punch = true;
            } else {
                kick = true;
            }
            moveLeft = false;
            moveRight = false;
            moveUp = false;
            moveDown = false;
            aiAttackCooldown = AI_ATTACK_INTERVAL_MIN_FRAMES
                + (int) (Math.random() * (AI_ATTACK_INTERVAL_MAX_FRAMES - AI_ATTACK_INTERVAL_MIN_FRAMES + 1));
            aiRetreatTimer = AI_RETREAT_MIN_FRAMES
                + (int) (Math.random() * (AI_RETREAT_MAX_FRAMES - AI_RETREAT_MIN_FRAMES + 1));
        }

        return PlayerInput.of(
            moveLeft,
            moveRight,
            moveUp,
            moveDown,
            punch,
            kick,
            block,
            false,
            false,
            false,
            false,
            false
        );
    }

    private void updateCombatState(Fighter fighter, boolean blockPressed, boolean punchPressed, boolean kickPressed) {
        if (fighter.cooldown > 0) fighter.cooldown--;
        if (fighter.attackRecoveryTimer > 0) fighter.attackRecoveryTimer--;
        if (fighter.attackFatigue > 0) fighter.attackFatigue--;
        if (fighter.blockCooldown > 0) fighter.blockCooldown--;
        if (fighter.blockWindowTimer > 0) fighter.blockWindowTimer--;

        if (fighter.stunTimer > 0) {
            fighter.stunTimer--;
            fighter.attack = AttackType.NONE;
            fighter.attackTimer = 0;
            fighter.hitApplied = false;
            fighter.blockWindowTimer = 0;
            return;
        }

        if (fighter.attack != AttackType.NONE) {
            fighter.attackTimer--;
            if (fighter.attackTimer <= 0) {
                fighter.attack = AttackType.NONE;
                fighter.hitApplied = false;
                fighter.attackRecoveryTimer = ATTACK_RECOVERY_FRAMES;
            }
            return;
        }

        if (blockPressed && fighter.blockCooldown <= 0) {
            fighter.blockWindowTimer = BLOCK_WINDOW_FRAMES;
            fighter.blockCooldown = BLOCK_COOLDOWN_FRAMES;
        }

        if (fighter.cooldown > 0 || fighter.attackRecoveryTimer > 0) return;

        if (kickPressed) {
            fighter.attack = AttackType.KICK;
            fighter.attackTimer = KICK_ACTIVE_FRAMES;
            fighter.cooldown = KICK_COOLDOWN + fighter.attackFatigue;
            fighter.hitApplied = false;
            fighter.attackFatigue = Math.min(ATTACK_FATIGUE_MAX, fighter.attackFatigue + ATTACK_FATIGUE_STEP);
            sounds.play(Sfx.KICK);
            return;
        }

        if (punchPressed) {
            fighter.attack = AttackType.PUNCH;
            fighter.attackTimer = PUNCH_ACTIVE_FRAMES;
            fighter.cooldown = PUNCH_COOLDOWN + fighter.attackFatigue;
            fighter.hitApplied = false;
            fighter.attackFatigue = Math.min(ATTACK_FATIGUE_MAX, fighter.attackFatigue + ATTACK_FATIGUE_STEP);
            sounds.play(Sfx.PUNCH);
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

        if (defender.blockWindowTimer > 0) {
            attacker.stunTimer = STUN_FRAMES;
            attacker.attack = AttackType.NONE;
            attacker.attackTimer = 0;
            attacker.hitApplied = true;
            attacker.attackRecoveryTimer = ATTACK_RECOVERY_FRAMES;
            defender.blockWindowTimer = 0;
            sounds.play(Sfx.HIT);
            return;
        }

        defender.health -= (attacker.attack == AttackType.PUNCH ? PUNCH_DAMAGE : KICK_DAMAGE);
        applyHitKnockback(attacker, defender);
        attacker.hitApplied = true;
        sounds.play(Sfx.HIT);
    }

    private void applyHitKnockback(Fighter attacker, Fighter defender) {
        float pushX = attacker.facing > 0 ? HIT_KNOCKBACK : -HIT_KNOCKBACK;
        defender.x = clamp(defender.x + pushX, -ARENA_HALF_WIDTH, ARENA_HALF_WIDTH);
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
            out.add(new FighterModel(name, mesh, paletteColor(i), computeCollisionRadius(mesh)));
        }
        return out;
    }

    private static float computeCollisionRadius(Mesh mesh) {
        float maxRadiusSq = 0.0f;
        for (MeshPart part : mesh.parts) {
            for (int i = 0; i < part.vertices.length; i += 5) {
                float x = part.vertices[i];
                float z = part.vertices[i + 2];
                float radiusSq = x * x + z * z;
                if (radiusSq > maxRadiusSq) {
                    maxRadiusSq = radiusSq;
                }
            }
        }
        float radius = (float) Math.sqrt(maxRadiusSq) * 0.34f;
        return Math.max(0.26f, Math.min(0.62f, radius));
    }

    private Mesh loadTerrainFloorMesh() {
        Path terrainPath = resolveTerrainFloorObj();
        if (terrainPath == null) {
            return null;
        }
        try {
            return ObjLoader.loadAndNormalize(terrainPath);
        } catch (IOException | IllegalStateException e) {
            return null;
        }
    }

    private Path resolveTerrainFloorObj() {
        Path[] candidates = {
            Path.of("fractal_terrain.obj"),
            Path.of("..", "fractal_terrain.obj")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
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

    static String normalizePortraitKey(String value) {
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
        return loadImageTexture(path);
    }

    private static int loadImageTexture(Path path) throws IOException {
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) {
            throw new IOException("Failed to decode image: " + path);
        }
        return uploadTexture(image);
    }

    private static int uploadTexture(BufferedImage image) {
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

    enum GameState {
        MODE_SELECT,
        MULTIPLAYER_ROLE_SELECT,
        MULTIPLAYER_WAITING,
        CHAR_SELECT,
        FIGHT
    }

    enum GameMode {
        SINGLE_PLAYER,
        MULTIPLAYER
    }

    private enum MusicTrack {
        MAIN_MENU("main-menu.mp3"),
        FIGHT("fight.mp3");

        final String fileName;

        MusicTrack(String fileName) {
            this.fileName = fileName;
        }
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
        private final EnumMap<MusicTrack, byte[]> musicCache = new EnumMap<>(MusicTrack.class);
        private final EnumSet<MusicTrack> failedMusicTracks = EnumSet.noneOf(MusicTrack.class);
        private final BlockingQueue<Sfx> queue = new LinkedBlockingQueue<>();
        private final List<Clip> activeClips = new CopyOnWriteArrayList<>();
        private final Object musicLock = new Object();
        private Clip musicClip;
        private MusicTrack currentMusic;
        private final Thread worker;

        private SoundEngine() {
            Path sfxRoot = resolveSfxRoot();
            Path musicRoot = resolveMusicRoot();
            log("Audio backend: Java Sound (Clip). OpenAL device/context is not used by this build.");
            log("SFX root resolved to: " + sfxRoot.toAbsolutePath());
            log("Music root resolved to: " + musicRoot.toAbsolutePath());
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
            for (MusicTrack track : MusicTrack.values()) {
                byte[] bytes = tryLoadFileBytes(musicRoot, track.fileName);
                if (bytes != null) {
                    musicCache.put(track, bytes);
                    log("Loaded music bytes for " + track.name() + " from " + track.fileName + " (" + bytes.length + " bytes)");
                } else {
                    log("No music found for " + track.name() + " at " + musicRoot.resolve(track.fileName).toAbsolutePath());
                }
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
            stopMusic();
            for (Clip clip : activeClips) {
                try {
                    clip.stop();
                    clip.close();
                } catch (Exception ignored) {
                }
            }
            activeClips.clear();
        }

        void setEnabled(boolean enabledValue) {
            enabled.set(enabledValue);
            if (!enabledValue) {
                queue.clear();
                stopMusic();
            }
        }

        void setMusic(MusicTrack track) {
            if (!enabled.get() || track == null) return;
            synchronized (musicLock) {
                if (track == currentMusic) return;
                if (failedMusicTracks.contains(track)) {
                    currentMusic = track;
                    return;
                }
                stopMusicLocked();
                if (startMusicLoopLocked(track)) {
                    currentMusic = track;
                } else {
                    failedMusicTracks.add(track);
                    currentMusic = track;
                }
            }
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

        private boolean startMusicLoopLocked(MusicTrack track) {
            byte[] encoded = musicCache.get(track);
            if (encoded == null) return false;
            try (
                ByteArrayInputStream bais = new ByteArrayInputStream(encoded);
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
                    log("Music line not supported: " + decodedFormat);
                    return false;
                }
                AudioInputStream decodedAis = AudioSystem.getAudioInputStream(decodedFormat, sourceAis);
                Clip clip = AudioSystem.getClip();
                clip.open(decodedAis);
                clip.loop(Clip.LOOP_CONTINUOUSLY);
                clip.start();
                decodedAis.close();
                musicClip = clip;
                log("Looping music track " + track.name());
                return true;
            } catch (Exception e) {
                log("Music playback failed for " + track.name() + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return false;
            }
        }

        private void stopMusic() {
            synchronized (musicLock) {
                stopMusicLocked();
            }
        }

        private void stopMusicLocked() {
            if (musicClip != null) {
                try {
                    musicClip.stop();
                    musicClip.close();
                } catch (Exception ignored) {
                }
                musicClip = null;
            }
            currentMusic = null;
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

        private static Path resolveMusicRoot() {
            Path cwd = Path.of("assets", "music");
            if (Files.isDirectory(cwd)) return cwd;
            Path nested = Path.of("final-project", "assets", "music");
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

        private static byte[] tryLoadFileBytes(Path root, String fileName) {
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

    enum NetworkMode {
        OFFLINE,
        HOST,
        CLIENT
    }

    enum AttackType {
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
        final boolean blockPressed;
        final boolean readyToggle;
        final boolean selectLeft;
        final boolean selectRight;
        final boolean confirm;
        final boolean back;


        private PlayerInput(
            boolean left,
            boolean right,
            boolean up,
            boolean down,
            boolean punchPressed,
            boolean kickPressed,
            boolean blockPressed,
            boolean readyToggle,
            boolean selectLeft,
            boolean selectRight,
            boolean confirm,
            boolean back

        ) {
            this.left = left;
            this.right = right;
            this.up = up;
            this.down = down;
            this.punchPressed = punchPressed;
            this.kickPressed = kickPressed;
            this.blockPressed = blockPressed;
            this.readyToggle = readyToggle;
            this.selectLeft = selectLeft;
            this.selectRight = selectRight;
            this.confirm = confirm;
            this.back = back;
        }

        static PlayerInput of(
            boolean left,
            boolean right,
            boolean up,
            boolean down,
            boolean punchPressed,
            boolean kickPressed,
            boolean blockPressed,
            boolean readyToggle,
            boolean selectLeft,
            boolean selectRight,
            boolean confirm,
            boolean back
        ) {
            return new PlayerInput(left, right, up, down, punchPressed, kickPressed, blockPressed, readyToggle, selectLeft, selectRight, confirm, back);
        }

        static PlayerInput empty() {
            return new PlayerInput(false, false, false, false, false, false, false, false, false, false, false, false);
        }

        String toWire() {
            return bool(left) + "|" + bool(right) + "|" + bool(up) + "|" + bool(down) + "|"
                + bool(punchPressed) + "|" + bool(kickPressed) + "|" + bool(blockPressed) + "|" + bool(readyToggle) + "|"
                + bool(selectLeft) + "|" + bool(selectRight) + "|" + bool(confirm) + "|" + bool(back);
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
                parseBool(parts[offset + 9]),
                parseBool(parts[offset + 10]),
                parseBool(parts[offset + 11])
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
            // host-side P2P: open socket and accept client connection
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
                        // ignore and continue accepting
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
                            if (parts.length < 13 || !"INPUT".equals(parts[0])) continue;
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
            // client P2P, connect directly to the host socket and sync
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

    static final class Fighter {
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
        int attackRecoveryTimer;
        int attackFatigue;
        int stunTimer;
        int blockWindowTimer;
        int blockCooldown;
    }

    record FighterModel(String name, Mesh mesh, float[] color, float collisionRadius) {}

    static final class Mesh {
        private final List<MeshPart> parts;

        private Mesh(List<MeshPart> parts) {
            this.parts = parts;
        }

        void render(float tintR, float tintG, float tintB) {
            glDisable(GL_CULL_FACE); // helps fix artifacts from glb to obj conversion
            for (MeshPart part : parts) {
                if (part.textureId != 0) {
                    glEnable(GL_TEXTURE_2D);
                    glBindTexture(GL_TEXTURE_2D, part.textureId);
                    glEnable(GL_ALPHA_TEST);
                    glAlphaFunc(GL_GREATER, 0.1f);
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

    record MeshPart(int textureId, float[] vertices) {}

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
            return loadImageTexture(path);
        }
    }

    private static final class MaterialInfo {
        int textureId = 0;
    }

    private record VertexRef(int vertexIndex, int uvIndex) {}

    private record TriangleRef(int textureId, VertexRef a, VertexRef b, VertexRef c) {}
}
