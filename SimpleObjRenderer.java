import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class SimpleObjRenderer {
    public static void main(String[] args) throws IOException {
        Path preferredRoot = Path.of("final-project", "models");
        Path modelsRoot = Files.exists(preferredRoot) ? preferredRoot : Path.of("final-project");
        List<Path> objFiles = findObjFiles(modelsRoot);
        if (objFiles.isEmpty()) {
            System.err.println("No OBJ files found under: " + modelsRoot.toAbsolutePath());
            return;
        }

        List<Scene> scenes = new ArrayList<>();
        for (Path obj : objFiles) {
            Scene scene = OBJParser.parse(obj);
            normalizeScene(scene);
            scenes.add(scene);
        }

        JFrame frame = new JFrame("Final Project - 3D Fighting Game");
        RendererPanel panel = new RendererPanel(scenes, objFiles);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setContentPane(panel);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        panel.requestFocusInWindow();
    }

    private static List<Path> findObjFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            Path base = root.toAbsolutePath().normalize();
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".obj"))
                .filter(path -> !path.toAbsolutePath().normalize().equals(base.resolve("SimpleObjRenderer.java")))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }
    }

    private static void normalizeScene(Scene scene) {
        if (scene.vertices.isEmpty()) return;

        Vector3 min = new Vector3(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        Vector3 max = new Vector3(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
        for (Vector3 v : scene.vertices) {
            min = new Vector3(Math.min(min.x, v.x), Math.min(min.y, v.y), Math.min(min.z, v.z));
            max = new Vector3(Math.max(max.x, v.x), Math.max(max.y, v.y), Math.max(max.z, v.z));
        }

        Vector3 center = min.add(max).multiply(0.5);
        double extentX = max.x - min.x;
        double extentY = max.y - min.y;
        double extentZ = max.z - min.z;
        double maxExtent = Math.max(extentX, Math.max(extentY, extentZ));
        if (maxExtent < 1e-9) maxExtent = 1.0;
        double scale = 1.7 / maxExtent;

        for (int i = 0; i < scene.vertices.size(); i++) {
            Vector3 v = scene.vertices.get(i);
            scene.vertices.set(i, v.subtract(center).multiply(scale));
        }
    }
}

class RendererPanel extends JPanel {
    private static final int WIDTH = 1100;
    private static final int HEIGHT = 760;
    private static final double FOV_DEGREES = 65.0;
    private static final double NEAR_Z = 0.05;

    private static final double ARENA_HALF_WIDTH = 2.8;
    private static final double ARENA_HALF_DEPTH = 1.3;
    private static final double MOVE_SPEED = 0.085;

    private static final int MAX_HEALTH = 100;
    private static final int PUNCH_DAMAGE = 8;
    private static final int KICK_DAMAGE = 14;
    private static final int PUNCH_ACTIVE_FRAMES = 6;
    private static final int KICK_ACTIVE_FRAMES = 10;
    private static final int PUNCH_RECOVERY_FRAMES = 18;
    private static final int KICK_RECOVERY_FRAMES = 28;
    private static final double PUNCH_RANGE = 1.35;
    private static final double KICK_RANGE = 1.65;

    private static final int KEY_COUNT = 1024;

    private final List<Scene> scenes;
    private final List<Path> scenePaths;

    private final BufferedImage frameBuffer;
    private final int[] pixels;
    private final double[] zBuffer;

    private final boolean[] keyDown = new boolean[KEY_COUNT];
    private final boolean[] keyPressedFrame = new boolean[KEY_COUNT];

    private final Fighter p1 = new Fighter();
    private final Fighter p2 = new Fighter();

    private final Font titleFont = new Font(Font.MONOSPACED, Font.BOLD, 30);
    private final Font uiFont = new Font(Font.MONOSPACED, Font.PLAIN, 16);

    private GameState state = GameState.CHAR_SELECT;
    private int winner = 0;

    RendererPanel(List<Scene> scenes, List<Path> scenePaths) {
        this.scenes = scenes;
        this.scenePaths = scenePaths;
        this.frameBuffer = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        this.pixels = frameBuffer.getRGB(0, 0, WIDTH, HEIGHT, null, 0, WIDTH);
        this.zBuffer = new double[WIDTH * HEIGHT];

        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setFocusable(true);
        setBackground(new Color(12, 14, 22));

        p1.selectedScene = 0;
        p2.selectedScene = scenes.size() > 1 ? 1 : 0;

        installInput();

        Timer timer = new Timer(16, e -> {
            tick();
            repaint();
        });
        timer.start();
    }

    private void installInput() {
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int code = e.getKeyCode();
                if (code >= 0 && code < KEY_COUNT) {
                    if (!keyDown[code]) {
                        keyPressedFrame[code] = true;
                    }
                    keyDown[code] = true;
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                int code = e.getKeyCode();
                if (code >= 0 && code < KEY_COUNT) {
                    keyDown[code] = false;
                }
            }
        });
    }

    private void tick() {
        if (state == GameState.CHAR_SELECT) {
            updateCharacterSelect();
        } else {
            updateFight();
        }

        Arrays.fill(keyPressedFrame, false);
    }

    private void updateCharacterSelect() {
        if (justPressed(KeyEvent.VK_A)) {
            p1.selectedScene = wrapScene(p1.selectedScene - 1);
            p1.ready = false;
        }
        if (justPressed(KeyEvent.VK_D)) {
            p1.selectedScene = wrapScene(p1.selectedScene + 1);
            p1.ready = false;
        }
        if (justPressed(KeyEvent.VK_LEFT)) {
            p2.selectedScene = wrapScene(p2.selectedScene - 1);
            p2.ready = false;
        }
        if (justPressed(KeyEvent.VK_RIGHT)) {
            p2.selectedScene = wrapScene(p2.selectedScene + 1);
            p2.ready = false;
        }

        if (justPressed(KeyEvent.VK_F)) p1.ready = !p1.ready;
        if (justPressed(KeyEvent.VK_SHIFT)) p2.ready = !p2.ready;

        if (p1.ready && p2.ready) {
            startRound();
        }
    }

    private void startRound() {
        p1.health = MAX_HEALTH;
        p2.health = MAX_HEALTH;
        p1.x = -1.25;
        p2.x = 1.25;
        p1.z = 0.0;
        p2.z = 0.0;
        p1.facingYaw = Math.toRadians(90.0);
        p2.facingYaw = Math.toRadians(-90.0);
        p1.attack = AttackType.NONE;
        p2.attack = AttackType.NONE;
        p1.attackTimer = 0;
        p2.attackTimer = 0;
        p1.cooldown = 0;
        p2.cooldown = 0;
        p1.hitAppliedThisAttack = false;
        p2.hitAppliedThisAttack = false;
        winner = 0;
        state = GameState.FIGHT;
    }

    private void updateFight() {
        if (winner != 0 && justPressed(KeyEvent.VK_ENTER)) {
            p1.ready = false;
            p2.ready = false;
            state = GameState.CHAR_SELECT;
            return;
        }

        if (winner != 0) return;

        updateMovement();
        updateAttacks();
        resolveCombat();

        if (p1.health <= 0) {
            p1.health = 0;
            winner = 2;
        } else if (p2.health <= 0) {
            p2.health = 0;
            winner = 1;
        }
    }

    private void updateMovement() {
        double p1dx = 0.0;
        double p1dz = 0.0;
        double p2dx = 0.0;
        double p2dz = 0.0;

        if (isDown(KeyEvent.VK_W)) p1dz -= MOVE_SPEED;
        if (isDown(KeyEvent.VK_S)) p1dz += MOVE_SPEED;
        if (isDown(KeyEvent.VK_A)) p1dx -= MOVE_SPEED;
        if (isDown(KeyEvent.VK_D)) p1dx += MOVE_SPEED;

        if (isDown(KeyEvent.VK_UP)) p2dz -= MOVE_SPEED;
        if (isDown(KeyEvent.VK_DOWN)) p2dz += MOVE_SPEED;
        if (isDown(KeyEvent.VK_LEFT)) p2dx -= MOVE_SPEED;
        if (isDown(KeyEvent.VK_RIGHT)) p2dx += MOVE_SPEED;

        p1.x = clamp(p1.x + p1dx, -ARENA_HALF_WIDTH, ARENA_HALF_WIDTH);
        p1.z = clamp(p1.z + p1dz, -ARENA_HALF_DEPTH, ARENA_HALF_DEPTH);
        p2.x = clamp(p2.x + p2dx, -ARENA_HALF_WIDTH, ARENA_HALF_WIDTH);
        p2.z = clamp(p2.z + p2dz, -ARENA_HALF_DEPTH, ARENA_HALF_DEPTH);

        p1.facingYaw = (p2.x >= p1.x) ? Math.toRadians(90.0) : Math.toRadians(-90.0);
        p2.facingYaw = (p1.x >= p2.x) ? Math.toRadians(90.0) : Math.toRadians(-90.0);
    }

    private void updateAttacks() {
        updateAttackState(p1, justPressed(KeyEvent.VK_F), justPressed(KeyEvent.VK_R));
        updateAttackState(p2, justPressed(KeyEvent.VK_PERIOD), justPressed(KeyEvent.VK_SHIFT));
    }

    private void updateAttackState(Fighter fighter, boolean punchPressed, boolean kickPressed) {
        if (fighter.cooldown > 0) fighter.cooldown--;

        if (fighter.attack != AttackType.NONE) {
            fighter.attackTimer--;
            if (fighter.attackTimer <= 0) {
                fighter.attack = AttackType.NONE;
                fighter.hitAppliedThisAttack = false;
            }
            return;
        }

        if (fighter.cooldown > 0) return;

        if (kickPressed) {
            fighter.attack = AttackType.KICK;
            fighter.attackTimer = KICK_ACTIVE_FRAMES;
            fighter.cooldown = KICK_RECOVERY_FRAMES;
            fighter.hitAppliedThisAttack = false;
            return;
        }

        if (punchPressed) {
            fighter.attack = AttackType.PUNCH;
            fighter.attackTimer = PUNCH_ACTIVE_FRAMES;
            fighter.cooldown = PUNCH_RECOVERY_FRAMES;
            fighter.hitAppliedThisAttack = false;
        }
    }

    private void resolveCombat() {
        tryApplyHit(p1, p2);
        tryApplyHit(p2, p1);
    }

    private void tryApplyHit(Fighter attacker, Fighter defender) {
        if (attacker.attack == AttackType.NONE || attacker.hitAppliedThisAttack) return;

        double dx = defender.x - attacker.x;
        double dz = defender.z - attacker.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        double maxRange = attacker.attack == AttackType.PUNCH ? PUNCH_RANGE : KICK_RANGE;
        if (dist > maxRange) return;

        boolean defenderInFront = (attacker.facingYaw > 0) ? (dx > -0.1) : (dx < 0.1);
        if (!defenderInFront) return;

        int damage = attacker.attack == AttackType.PUNCH ? PUNCH_DAMAGE : KICK_DAMAGE;
        defender.health -= damage;
        attacker.hitAppliedThisAttack = true;
    }

    private int wrapScene(int idx) {
        int m = idx % scenes.size();
        if (m < 0) m += scenes.size();
        return m;
    }

    private boolean isDown(int keyCode) {
        return keyCode >= 0 && keyCode < KEY_COUNT && keyDown[keyCode];
    }

    private boolean justPressed(int keyCode) {
        return keyCode >= 0 && keyCode < KEY_COUNT && keyPressedFrame[keyCode];
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (state == GameState.CHAR_SELECT) {
            renderCharacterSelect();
        } else {
            renderFightScene();
        }

        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.drawImage(frameBuffer, 0, 0, null);

        if (state == GameState.CHAR_SELECT) {
            drawCharacterSelectOverlay(g2d);
        } else {
            drawFightOverlay(g2d);
        }
    }

    private void renderCharacterSelect() {
        clearBuffers(new Color(8, 11, 18).getRGB());
        drawSkyAndFloor();

        double t = System.nanoTime() * 1e-9;
        renderSceneInstance(
            scenes.get(p1.selectedScene),
            p1.selectedScene,
            Math.sin(t * 1.5) * 0.2,
            0.0,
            -2.2,
            0.0,
            0xC0FFD4
        );

        renderSceneInstance(
            scenes.get(p2.selectedScene),
            p2.selectedScene,
            Math.PI + Math.sin(t * 1.3) * 0.2,
            0.0,
            2.2,
            0.0,
            0xFFD9C0
        );

        frameBuffer.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);
    }

    private void renderFightScene() {
        clearBuffers(new Color(12, 14, 22).getRGB());
        drawSkyAndFloor();
        drawArenaLines();

        double p1AttackLean = attackLean(p1);
        double p2AttackLean = attackLean(p2);

        renderSceneInstance(
            scenes.get(p1.selectedScene),
            p1.selectedScene,
            p1.facingYaw,
            p1AttackLean,
            p1.x,
            p1.z,
            0xC0FFD4
        );

        renderSceneInstance(
            scenes.get(p2.selectedScene),
            p2.selectedScene,
            p2.facingYaw,
            p2AttackLean,
            p2.x,
            p2.z,
            0xFFD9C0
        );

        frameBuffer.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);
    }

    private void clearBuffers(int backgroundColor) {
        Arrays.fill(pixels, backgroundColor);
        Arrays.fill(zBuffer, Double.POSITIVE_INFINITY);
    }

    private void drawSkyAndFloor() {
        for (int y = 0; y < HEIGHT; y++) {
            double t = (double) y / (HEIGHT - 1);
            int r = (int) Math.round(20 + (t * 25));
            int g = (int) Math.round(26 + (t * 30));
            int b = (int) Math.round(45 + (t * 40));
            int color = (r << 16) | (g << 8) | b;
            int rowStart = y * WIDTH;
            Arrays.fill(pixels, rowStart, rowStart + WIDTH, color);
        }
    }

    private void drawArenaLines() {
        for (double x = -ARENA_HALF_WIDTH; x <= ARENA_HALF_WIDTH + 1e-6; x += 0.7) {
            drawWorldLine(x, -ARENA_HALF_DEPTH, x, ARENA_HALF_DEPTH, 0x2C3246);
        }
        for (double z = -ARENA_HALF_DEPTH; z <= ARENA_HALF_DEPTH + 1e-6; z += 0.5) {
            drawWorldLine(-ARENA_HALF_WIDTH, z, ARENA_HALF_WIDTH, z, 0x2C3246);
        }

        drawWorldLine(-ARENA_HALF_WIDTH, -ARENA_HALF_DEPTH, ARENA_HALF_WIDTH, -ARENA_HALF_DEPTH, 0xD8DFE8);
        drawWorldLine(-ARENA_HALF_WIDTH, ARENA_HALF_DEPTH, ARENA_HALF_WIDTH, ARENA_HALF_DEPTH, 0xD8DFE8);
        drawWorldLine(-ARENA_HALF_WIDTH, -ARENA_HALF_DEPTH, -ARENA_HALF_WIDTH, ARENA_HALF_DEPTH, 0xD8DFE8);
        drawWorldLine(ARENA_HALF_WIDTH, -ARENA_HALF_DEPTH, ARENA_HALF_WIDTH, ARENA_HALF_DEPTH, 0xD8DFE8);
    }

    private void drawWorldLine(double x0, double z0, double x1, double z1, int color) {
        ProjectedVertex a = project(new Vector3(x0, -1.02, z0), null);
        ProjectedVertex b = project(new Vector3(x1, -1.02, z1), null);
        if (a == null || b == null) return;
        drawLine(a.x, a.y, b.x, b.y, color);
    }

    private double attackLean(Fighter fighter) {
        if (fighter.attack == AttackType.NONE) return 0.0;
        if (fighter.attack == AttackType.PUNCH) return 0.20;
        return 0.30;
    }

    private void renderSceneInstance(
        Scene scene,
        int sceneIndex,
        double yaw,
        double attackLean,
        double worldX,
        double worldZ,
        int fallbackTint
    ) {
        Vector3 lightDir = new Vector3(0.65, 1.0, -0.45).normalize();
        int sceneBaseColor = mixColor(pickColor(sceneIndex), fallbackTint, 0.35);

        for (Face face : scene.faces) {
            if (face.vertices.length < 3) continue;
            Material material = scene.materials.get(face.materialName);
            int baseColor = material != null ? material.diffuseColor : sceneBaseColor;
            BufferedImage texture = material != null ? material.diffuseTexture : null;

            for (int i = 1; i < face.vertices.length - 1; i++) {
                Vector3 a = scene.vertices.get(face.vertices[0]);
                Vector3 b = scene.vertices.get(face.vertices[i]);
                Vector3 c = scene.vertices.get(face.vertices[i + 1]);

                Vector3 wa = placeVertex(a, yaw, attackLean, worldX, worldZ);
                Vector3 wb = placeVertex(b, yaw, attackLean, worldX, worldZ);
                Vector3 wc = placeVertex(c, yaw, attackLean, worldX, worldZ);

                if (wa == null || wb == null || wc == null) continue;

                Vector3 n = wb.subtract(wa).cross(wc.subtract(wa)).normalize();
                double intensity = 0.24 + 0.76 * Math.max(0.0, n.dot(lightDir.multiply(-1.0)));
                int shaded = scaleColor(baseColor, intensity);

                Vector2 ta = getFaceUv(scene, face, 0);
                Vector2 tb = getFaceUv(scene, face, i);
                Vector2 tc = getFaceUv(scene, face, i + 1);

                ProjectedVertex pa = project(wa, ta);
                ProjectedVertex pb = project(wb, tb);
                ProjectedVertex pc = project(wc, tc);
                if (pa == null || pb == null || pc == null) continue;

                rasterizeTriangle(pa, pb, pc, shaded, intensity, texture);
            }
        }
    }

    private Vector3 placeVertex(Vector3 local, double yaw, double attackLean, double worldX, double worldZ) {
        Vector3 rotated = rotate(local, 0.0, yaw);

        if (attackLean > 0.0) {
            double forwardSign = yaw > 0 ? 1.0 : -1.0;
            double push = attackLean * (1.0 - Math.max(0.0, rotated.y + 0.2));
            rotated = new Vector3(rotated.x + forwardSign * push, rotated.y, rotated.z);
        }

        Vector3 world = rotated.add(new Vector3(worldX, -0.15, worldZ));
        return toCameraSpace(world);
    }

    private Vector3 toCameraSpace(Vector3 world) {
        Vector3 cameraTranslated = world.add(new Vector3(0.0, -0.2, -6.0));
        if (-cameraTranslated.z < NEAR_Z) return null;
        return cameraTranslated;
    }

    private Vector3 rotate(Vector3 v, double pitch, double yaw) {
        double cosY = Math.cos(yaw);
        double sinY = Math.sin(yaw);
        double x1 = cosY * v.x + sinY * v.z;
        double z1 = -sinY * v.x + cosY * v.z;

        double cosX = Math.cos(pitch);
        double sinX = Math.sin(pitch);
        double y2 = cosX * v.y - sinX * z1;
        double z2 = sinX * v.y + cosX * z1;

        return new Vector3(x1, y2, z2);
    }

    private ProjectedVertex project(Vector3 v, Vector2 uv) {
        double aspect = (double) WIDTH / HEIGHT;
        double f = 1.0 / Math.tan(Math.toRadians(FOV_DEGREES) * 0.5);

        double invZ = 1.0 / (-v.z);
        double ndcX = (v.x * f / aspect) * invZ;
        double ndcY = (v.y * f) * invZ;
        int sx = (int) ((ndcX + 1.0) * 0.5 * WIDTH);
        int sy = (int) ((1.0 - (ndcY + 1.0) * 0.5) * HEIGHT);
        if (uv == null) {
            return new ProjectedVertex(sx, sy, -v.z, 0.0, 0.0, false);
        }
        return new ProjectedVertex(sx, sy, -v.z, uv.x, uv.y, true);
    }

    private void rasterizeTriangle(ProjectedVertex a, ProjectedVertex b, ProjectedVertex c, int color, double intensity, BufferedImage texture) {
        int minX = clamp((int) Math.floor(Math.min(a.x, Math.min(b.x, c.x))), 0, WIDTH - 1);
        int maxX = clamp((int) Math.ceil(Math.max(a.x, Math.max(b.x, c.x))), 0, WIDTH - 1);
        int minY = clamp((int) Math.floor(Math.min(a.y, Math.min(b.y, c.y))), 0, HEIGHT - 1);
        int maxY = clamp((int) Math.ceil(Math.max(a.y, Math.max(b.y, c.y))), 0, HEIGHT - 1);

        double area = edgeFunction(a.x, a.y, b.x, b.y, c.x, c.y);
        if (Math.abs(area) < 1e-9) return;

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double w0 = edgeFunction(b.x, b.y, c.x, c.y, x, y);
                double w1 = edgeFunction(c.x, c.y, a.x, a.y, x, y);
                double w2 = edgeFunction(a.x, a.y, b.x, b.y, x, y);

                if ((w0 >= 0 && w1 >= 0 && w2 >= 0 && area > 0) ||
                    (w0 <= 0 && w1 <= 0 && w2 <= 0 && area < 0)) {
                    double alpha = w0 / area;
                    double beta = w1 / area;
                    double gamma = w2 / area;
                    double depth = alpha * a.depth + beta * b.depth + gamma * c.depth;

                    int idx = y * WIDTH + x;
                    if (depth < zBuffer[idx]) {
                        zBuffer[idx] = depth;
                        if (texture != null && a.hasUv && b.hasUv && c.hasUv) {
                            double u = alpha * a.u + beta * b.u + gamma * c.u;
                            double v = alpha * a.v + beta * b.v + gamma * c.v;
                            int sample = sampleTexture(texture, u, v);
                            pixels[idx] = scaleColor(sample, intensity);
                        } else {
                            pixels[idx] = color;
                        }
                    }
                }
            }
        }
    }

    private int sampleTexture(BufferedImage texture, double u, double v) {
        u = Math.max(0.0, Math.min(1.0, u));
        v = Math.max(0.0, Math.min(1.0, v));
        int tx = (int) Math.round(u * (texture.getWidth() - 1));
        int ty = (int) Math.round((1.0 - v) * (texture.getHeight() - 1));
        tx = clamp(tx, 0, texture.getWidth() - 1);
        ty = clamp(ty, 0, texture.getHeight() - 1);
        return texture.getRGB(tx, ty) & 0xFFFFFF;
    }

    private void drawLine(int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;

        int x = x0;
        int y = y0;
        while (true) {
            if (x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT) {
                pixels[y * WIDTH + x] = color;
            }
            if (x == x1 && y == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y += sy;
            }
        }
    }

    private double edgeFunction(int ax, int ay, int bx, int by, int px, int py) {
        return (double) (px - ax) * (by - ay) - (double) (py - ay) * (bx - ax);
    }

    private int scaleColor(int rgb, double intensity) {
        intensity = Math.max(0.0, Math.min(1.0, intensity));
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        r = (int) Math.round(r * intensity);
        g = (int) Math.round(g * intensity);
        b = (int) Math.round(b * intensity);
        return (r << 16) | (g << 8) | b;
    }

    private int mixColor(int a, int b, double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;

        int r = (int) Math.round(ar * (1.0 - t) + br * t);
        int g = (int) Math.round(ag * (1.0 - t) + bg * t);
        int blue = (int) Math.round(ab * (1.0 - t) + bb * t);
        return (r << 16) | (g << 8) | blue;
    }

    private int pickColor(int index) {
        int[] palette = {
            0xE08B5D,
            0x82B26B,
            0x67A8C8,
            0xD8B65B,
            0xC67FB2,
            0x77D1B6
        };
        return palette[index % palette.length];
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private Vector2 getFaceUv(Scene scene, Face face, int idxInFace) {
        if (face.uvs == null || idxInFace < 0 || idxInFace >= face.uvs.length) return null;
        int uvIndex = face.uvs[idxInFace];
        if (uvIndex < 0 || uvIndex >= scene.uvs.size()) return null;
        return scene.uvs.get(uvIndex);
    }

    private String modelName(int sceneIdx) {
        Path p = scenePaths.get(sceneIdx);
        Path parent = p.getParent();
        if (parent != null) {
            Path last = parent.getFileName();
            if (last != null) return last.toString();
        }
        String file = p.getFileName().toString();
        return file.replace(".obj", "");
    }

    private void drawCharacterSelectOverlay(Graphics2D g2d) {
        g2d.setColor(new Color(235, 240, 248));
        g2d.setFont(titleFont);
        g2d.drawString("3D FIGHTER - CHARACTER SELECT", 250, 70);

        g2d.setFont(uiFont);
        drawCard(g2d, 70, 120, 420, 520, new Color(25, 40, 52, 220), "PLAYER 1", modelName(p1.selectedScene),
            "A/D: choose", "F: ready", p1.ready);

        drawCard(g2d, 610, 120, 420, 520, new Color(52, 31, 26, 220), "PLAYER 2", modelName(p2.selectedScene),
            "LEFT/RIGHT: choose", "SHIFT: ready", p2.ready);

        g2d.setColor(new Color(220, 225, 236));
        g2d.drawString("When both players are READY, the fight starts automatically.", 300, 710);
    }

    private void drawCard(
        Graphics2D g2d,
        int x,
        int y,
        int w,
        int h,
        Color fill,
        String player,
        String fighter,
        String controls1,
        String controls2,
        boolean ready
    ) {
        g2d.setColor(fill);
        g2d.fillRoundRect(x, y, w, h, 18, 18);
        g2d.setColor(new Color(240, 246, 252));
        g2d.drawRoundRect(x, y, w, h, 18, 18);

        g2d.setFont(new Font(Font.MONOSPACED, Font.BOLD, 24));
        g2d.drawString(player, x + 24, y + 42);

        g2d.setFont(new Font(Font.MONOSPACED, Font.BOLD, 20));
        g2d.drawString("Fighter: " + fighter, x + 24, y + 90);

        g2d.setFont(uiFont);
        g2d.drawString(controls1, x + 24, y + 140);
        g2d.drawString(controls2, x + 24, y + 170);

        g2d.setFont(new Font(Font.MONOSPACED, Font.BOLD, 28));
        if (ready) {
            g2d.setColor(new Color(130, 232, 152));
            g2d.drawString("READY", x + 24, y + h - 30);
        } else {
            g2d.setColor(new Color(255, 179, 112));
            g2d.drawString("NOT READY", x + 24, y + h - 30);
        }
    }

    private void drawFightOverlay(Graphics2D g2d) {
        g2d.setFont(uiFont);
        drawHealthBar(g2d, 30, 25, 460, 24, p1.health, new Color(95, 230, 145), "P1 " + modelName(p1.selectedScene));
        drawHealthBar(g2d, WIDTH - 490, 25, 460, 24, p2.health, new Color(233, 135, 112), "P2 " + modelName(p2.selectedScene));

        g2d.setColor(new Color(220, 227, 240));
        g2d.drawString("P1 Move: WASD  Attack: F(punch), R(kick)", 25, HEIGHT - 42);
        g2d.drawString("P2 Move: Arrow Keys  Attack: .(punch), SHIFT(kick)", 25, HEIGHT - 20);

        if (winner != 0) {
            g2d.setColor(new Color(18, 18, 24, 200));
            g2d.fillRoundRect(330, 280, 440, 160, 20, 20);
            g2d.setColor(new Color(246, 246, 252));
            g2d.drawRoundRect(330, 280, 440, 160, 20, 20);
            g2d.setFont(titleFont);
            g2d.drawString("PLAYER " + winner + " WINS", 390, 345);
            g2d.setFont(uiFont);
            g2d.drawString("Press ENTER to return to character select", 380, 390);
        }
    }

    private void drawHealthBar(Graphics2D g2d, int x, int y, int w, int h, int hp, Color fill, String label) {
        g2d.setColor(new Color(18, 22, 34, 180));
        g2d.fillRoundRect(x, y, w, h, 10, 10);
        g2d.setColor(new Color(238, 242, 248));
        g2d.drawRoundRect(x, y, w, h, 10, 10);

        int innerW = (int) Math.round((w - 4) * (Math.max(0, hp) / (double) MAX_HEALTH));
        g2d.setColor(fill);
        g2d.fillRoundRect(x + 2, y + 2, innerW, h - 4, 8, 8);

        g2d.setColor(new Color(240, 244, 248));
        g2d.drawString(label + " - HP: " + hp, x, y - 8);
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

    private static class Fighter {
        int selectedScene;
        boolean ready;

        double x;
        double z;
        double facingYaw;
        int health = MAX_HEALTH;

        AttackType attack = AttackType.NONE;
        int attackTimer = 0;
        int cooldown = 0;
        boolean hitAppliedThisAttack = false;
    }
}

class ProjectedVertex {
    final int x;
    final int y;
    final double depth;
    final double u;
    final double v;
    final boolean hasUv;

    ProjectedVertex(int x, int y, double depth, double u, double v, boolean hasUv) {
        this.x = x;
        this.y = y;
        this.depth = depth;
        this.u = u;
        this.v = v;
        this.hasUv = hasUv;
    }
}

class Scene {
    final ArrayList<Vector3> vertices = new ArrayList<>();
    final ArrayList<Vector2> uvs = new ArrayList<>();
    final ArrayList<Face> faces = new ArrayList<>();
    final Map<String, Material> materials = new HashMap<>();
}

class Face {
    final int[] vertices;
    final int[] uvs;
    final String materialName;

    Face(int[] vertices, int[] uvs, String materialName) {
        this.vertices = vertices;
        this.uvs = uvs;
        this.materialName = materialName;
    }
}

class Vector2 {
    final double x;
    final double y;

    Vector2(double x, double y) {
        this.x = x;
        this.y = y;
    }
}

class Vector3 {
    final double x;
    final double y;
    final double z;

    Vector3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    Vector3 add(Vector3 v) {
        return new Vector3(x + v.x, y + v.y, z + v.z);
    }

    Vector3 subtract(Vector3 v) {
        return new Vector3(x - v.x, y - v.y, z - v.z);
    }

    Vector3 multiply(double scalar) {
        return new Vector3(x * scalar, y * scalar, z * scalar);
    }

    Vector3 cross(Vector3 v) {
        return new Vector3(
            y * v.z - z * v.y,
            z * v.x - x * v.z,
            x * v.y - y * v.x
        );
    }

    double dot(Vector3 v) {
        return x * v.x + y * v.y + z * v.z;
    }

    Vector3 normalize() {
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length < 1e-12) return new Vector3(0.0, 0.0, 0.0);
        return new Vector3(x / length, y / length, z / length);
    }
}

class Material {
    int diffuseColor = 0xCCCCCC;
    BufferedImage diffuseTexture;
}

class OBJParser {
    public static Scene parse(Path filePath) throws IOException {
        Scene scene = new Scene();
        String currentMaterial = null;
        Path objDir = filePath.toAbsolutePath().normalize().getParent();
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] tokens = line.split("\\s+");
                switch (tokens[0]) {
                    case "v" -> scene.vertices.add(new Vector3(
                        Double.parseDouble(tokens[1]),
                        Double.parseDouble(tokens[2]),
                        Double.parseDouble(tokens[3])
                    ));
                    case "vt" -> scene.uvs.add(new Vector2(
                        Double.parseDouble(tokens[1]),
                        Double.parseDouble(tokens[2])
                    ));
                    case "mtllib" -> {
                        if (objDir != null && tokens.length >= 2) {
                            Path mtlPath = objDir.resolve(tokens[1]).normalize();
                            if (Files.exists(mtlPath)) {
                                parseMtl(mtlPath, scene, objDir);
                            }
                        }
                    }
                    case "usemtl" -> {
                        if (tokens.length >= 2) {
                            currentMaterial = tokens[1];
                        }
                    }
                    case "f" -> {
                        int count = tokens.length - 1;
                        int[] faceVertices = new int[count];
                        int[] faceUvs = new int[count];
                        for (int i = 0; i < count; i++) {
                            String[] faceData = tokens[i + 1].split("/");
                            int rawIndex = Integer.parseInt(faceData[0]);
                            int resolved = resolveObjIndex(rawIndex, scene.vertices.size());
                            faceVertices[i] = resolved;
                            faceUvs[i] = parseUvIndex(faceData, scene.uvs.size());
                        }
                        scene.faces.add(new Face(faceVertices, faceUvs, currentMaterial));
                    }
                    default -> {
                    }
                }
            }
        }
        return scene;
    }

    private static int resolveObjIndex(int index, int vertexCount) {
        if (index > 0) return index - 1;
        if (index < 0) return vertexCount + index;
        throw new IllegalArgumentException("OBJ vertex index cannot be 0");
    }

    private static int parseUvIndex(String[] faceData, int uvCount) {
        if (faceData.length < 2 || faceData[1].isBlank()) return -1;
        int raw = Integer.parseInt(faceData[1]);
        if (raw > 0) return raw - 1;
        if (raw < 0) return uvCount + raw;
        return -1;
    }

    private static void parseMtl(Path mtlPath, Scene scene, Path objDir) throws IOException {
        String currentName = null;
        Material current = null;
        try (BufferedReader reader = Files.newBufferedReader(mtlPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] tokens = line.split("\\s+");
                switch (tokens[0]) {
                    case "newmtl" -> {
                        if (tokens.length >= 2) {
                            currentName = tokens[1];
                            current = new Material();
                            scene.materials.put(currentName, current);
                        }
                    }
                    case "Kd" -> {
                        if (current != null && tokens.length >= 4) {
                            int r = clamp255(Double.parseDouble(tokens[1]) * 255.0);
                            int g = clamp255(Double.parseDouble(tokens[2]) * 255.0);
                            int b = clamp255(Double.parseDouble(tokens[3]) * 255.0);
                            current.diffuseColor = (r << 16) | (g << 8) | b;
                        }
                    }
                    case "map_Kd" -> {
                        if (current != null && tokens.length >= 2 && objDir != null) {
                            Path texPath = objDir.resolve(tokens[1]).normalize();
                            if (Files.exists(texPath)) {
                                current.diffuseTexture = ImageIO.read(texPath.toFile());
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
        }
    }

    private static int clamp255(double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }
}
