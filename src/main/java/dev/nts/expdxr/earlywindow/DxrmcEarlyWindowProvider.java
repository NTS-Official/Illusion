package dev.nts.expdxr.earlywindow;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.loading.ProgramArgs;
import net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLPixels;
import org.lwjgl.sdl.SDLRender;
import org.lwjgl.sdl.SDLSurface;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P268/P271: DX RMC early window provider.
 *
 * <p>Creates the SDL window that Minecraft later adopts (see {@code WindowAdoptCreatedMixin}), and paints the
 * Illusion loading screen into it. The screen itself is
 * composed with AWT (logo + purple bar + caption) and uploaded as one streaming texture, so no font or
 * image codec dependency is added to the loader phase.</p>
 */
public final class DxrmcEarlyWindowProvider implements ImmediateWindowProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger("dxrmc/earlywindow");

    private static final int INITIAL_WIDTH = 854;
    private static final int INITIAL_HEIGHT = 480;

    private static final String LOGO_RESOURCE = "/assets/dxrmc/logo_flat_dark.jpg";
    /** Minecraft's own default (Mojangles) glyph sheet: 16x16 cells of 8x8 pixels, U+0000..U+00FF. */
    private static final String MOJANGLES_RESOURCE = "/assets/dxrmc/mojangles_ascii.png";
    private static final String CAPTION = "Initializing DirectX 12 backend...";

    /** Bright purple progress fill (P271). */
    private static final Color BAR_FILL = new Color(0xC4, 0x6B, 0xFF);
    private static final Color BAR_TRACK = new Color(0x2E, 0x32, 0x38);
    private static final Color BACKGROUND = new Color(0x00, 0x00, 0x00);
    private static final Color CAPTION_COLOR = new Color(0xD8, 0xDC, 0xE2);

    /**
     * P269: channel to {@code WindowAdoptCreatedMixin}. The provider lives in the loader's early-service class
     * loader and the mixin in the game's, so they cannot exchange statics; a system property is readable from
     * both. The mixin clears the handle on take, so no stale handle can be adopted by a later window.
     */
    private static final String HANDLE_PROPERTY = "dxrmc.earlywindow.handle";
    /** Written by the mixin when the game really adopted this window (D3D12 backend only). */
    private static final String ADOPTED_PROPERTY = "dxrmc.earlywindow.adopted";

    private long window;
    private long renderer;
    private BufferedImage logo;
    private BufferedImage mojangles;
    private boolean textureFailureLogged;
    private boolean handedOver;
    private int progressTicks;

    @Override
    public String name() {
        return "dxrmc";
    }

    @Override
    public void initialize(ProgramArgs args) {
        if (!SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO)) {
            LOGGER.error("SDL_Init failed: {}", SDLError.SDL_GetError());
            return;
        }
        window = SDLVideo.SDL_CreateWindow("Minecraft* NeoForge", INITIAL_WIDTH, INITIAL_HEIGHT,
                SDLVideo.SDL_WINDOW_RESIZABLE);
        if (window == 0L) {
            LOGGER.error("SDL_CreateWindow failed: {}", SDLError.SDL_GetError());
            return;
        }
        SDLVideo.SDL_SetWindowPosition(window, SDLVideo.SDL_WINDOWPOS_CENTERED, SDLVideo.SDL_WINDOWPOS_CENTERED);
        renderer = SDLRender.SDL_CreateRenderer(window, "software");
        if (renderer == 0L) {
            LOGGER.warn("software renderer unavailable ({}), using default renderer",
                    SDLError.SDL_GetError());
            renderer = SDLRender.SDL_CreateRenderer(window, (CharSequence) null);
        }
        SDLVideo.SDL_ShowWindow(window);
        logo = loadLogo();
        mojangles = loadImage(MOJANGLES_RESOURCE);
        System.setProperty(HANDLE_PROPERTY, Long.toString(window));
        LOGGER.info("early window created handle={} renderer={} (single-window mode)", window, renderer);
        LOGGER.info("loading screen: logo={} bar=purple caption='{}' (Mojangles glyphs={})", logo != null ? logo.getWidth() + "x" + logo.getHeight() : "missing", CAPTION, mojangles != null ? mojangles.getWidth() + "x" + mojangles.getHeight() : "missing");
        redraw();
    }

    private BufferedImage loadLogo() {
        try (InputStream in = DxrmcEarlyWindowProvider.class.getResourceAsStream(LOGO_RESOURCE)) {
            if (in == null) {
                LOGGER.warn("logo resource {} is missing from the early-window jar", LOGO_RESOURCE);
                return null;
            }
            return ImageIO.read(in);
        } catch (Throwable t) {
            LOGGER.warn("cannot decode the loading screen logo: {}", t.toString());
            return null;
        }
    }

    private void redraw() {
        if (renderer == 0L || handedOver) {
            return;
        }
        int width = INITIAL_WIDTH;
        int height = INITIAL_HEIGHT;

        BufferedImage frame = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = frame.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, width, height);

            int margin = Math.round(width * 0.10f);
            int contentWidth = width - margin * 2;

            if (logo != null) {
                float maxLogoWidth = contentWidth;
                float maxLogoHeight = height * 0.34f;
                float scale = Math.min(maxLogoWidth / logo.getWidth(), maxLogoHeight / logo.getHeight());
                int logoWidth = Math.round(logo.getWidth() * scale);
                int logoHeight = Math.round(logo.getHeight() * scale);
                g.drawImage(logo, (width - logoWidth) / 2, Math.round(height * 0.08f), logoWidth, logoHeight,
                        null);
            }

            int barHeight = Math.max(8, Math.round(height * 0.022f));
            int barY = Math.round(height * 0.62f);
            g.setColor(BAR_TRACK);
            g.fillRoundRect(margin, barY, contentWidth, barHeight, barHeight, barHeight);

            float fraction = Math.min(1.0f, 0.05f + progressTicks * 0.02f);
            int filled = Math.max(barHeight, Math.round(contentWidth * fraction));
            g.setColor(BAR_FILL);
            g.fillRoundRect(margin, barY, filled, barHeight, barHeight, barHeight);

            // P272: the caption uses Minecraft's own glyph sheet, not a system font - the loading screen then looks
            // like the in-game UI (Mojangles) instead of an AWT fallback.
            int captionScale = Math.max(2, Math.round(height * 0.036f) / 8);
            drawMojangles(g, CAPTION, width, barY + barHeight + Math.round(18 * captionScale / 2f), captionScale);
        } finally {
            g.dispose();
        }

        presentFrame(frame);
    }

    private BufferedImage loadImage(String resource) {
        try (InputStream in = DxrmcEarlyWindowProvider.class.getResourceAsStream(resource)) {
            if (in == null) {
                LOGGER.warn("{} is missing from the early-window jar", resource);
                return null;
            }
            return ImageIO.read(in);
        } catch (Throwable t) {
            LOGGER.warn("cannot decode {}: {}", resource, t.toString());
            return null;
        }
    }

    /**
     * P272: draws {@code text} with Minecraft's own default font. The glyph sheet is the vanilla
     * {@code assets/minecraft/textures/font/ascii.png} atlas (16x16 cells of 8x8 pixels); every glyph is advanced
     * by its last non-empty column, which is what the vanilla font renderer reports as the glyph width.
     */
    private void drawMojangles(Graphics2D g, String text, int canvasWidth, int baselineY, int scale) {
        if (mojangles == null) {
            return;
        }
        int totalWidth = 0;
        for (int i = 0; i < text.length(); i++) {
            totalWidth += glyphAdvance(text.charAt(i)) * scale;
        }
        int x = (canvasWidth - totalWidth) / 2;
        int top = baselineY - 8 * scale;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int advance = glyphAdvance(c);
            drawGlyph(g, c, x, top, scale);
            x += advance * scale;
        }
        g.setColor(CAPTION_COLOR);
    }

    private int glyphAdvance(char c) {
        int cellX = (c & 0x0F) * 8;
        int cellY = ((c >> 4) & 0x0F) * 8;
        int lastColumn = 1;
        for (int column = 0; column < 8; column++) {
            for (int row = 0; row < 8; row++) {
                if ((mojangles.getRGB(cellX + column, cellY + row) >>> 24) != 0) {
                    lastColumn = Math.max(lastColumn, column + 1);
                }
            }
        }
        return lastColumn + 1;
    }

    private void drawGlyph(Graphics2D g, char c, int x, int top, int scale) {
        int cellX = (c & 0x0F) * 8;
        int cellY = ((c >> 4) & 0x0F) * 8;
        g.setColor(new Color(0x3F, 0x3F, 0x3F));
        for (int row = 0; row < 8; row++) {
            for (int column = 0; column < 8; column++) {
                if ((mojangles.getRGB(cellX + column, cellY + row) >>> 24) != 0) {
                    g.fillRect(x + column * scale + scale, top + row * scale + scale, scale, scale);
                }
            }
        }
        g.setColor(CAPTION_COLOR);
        for (int row = 0; row < 8; row++) {
            for (int column = 0; column < 8; column++) {
                if ((mojangles.getRGB(cellX + column, cellY + row) >>> 24) != 0) {
                    g.fillRect(x + column * scale, top + row * scale, scale, scale);
                }
            }
        }
    }
    /**
     * P271: blit an AWT frame through a throw-away SDL surface/texture pair. Going through
     * {@code SDL_CreateTextureFromSurface} keeps the pixel format negotiation inside SDL instead of relying on
     * a streaming texture upload, which the software renderer silently ignored.
     */
    private void presentFrame(BufferedImage frame) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        int[] argb = frame.getRGB(0, 0, width, height, null, 0, width);
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try {
            pixels.asIntBuffer().put(argb);
            long surface = SDLSurface.nSDL_CreateSurfaceFrom(width, height, SDLPixels.SDL_PIXELFORMAT_ARGB8888,
                    MemoryUtil.memAddress(pixels), width * 4);
            if (surface == 0L) {
                logTextureFailure("SDL_CreateSurfaceFrom");
                return;
            }
            long texture = SDLRender.nSDL_CreateTextureFromSurface(renderer, surface);
            if (texture == 0L) {
                logTextureFailure("SDL_CreateTextureFromSurface");
                SDLSurface.nSDL_DestroySurface(surface);
                return;
            }
            SDLRender.nSDL_RenderTexture(renderer, texture, 0L, 0L);
            SDLRender.nSDL_DestroyTexture(texture);
            SDLSurface.nSDL_DestroySurface(surface);
        } finally {
            MemoryUtil.memFree(pixels);
        }
        SDLRender.SDL_RenderPresent(renderer);
    }

    private void logTextureFailure(String what) {
        if (!textureFailureLogged) {
            textureFailureLogged = true;
            LOGGER.warn("{} failed: {}", what, SDLError.SDL_GetError());
        }
    }

    @Override
    public void setMinecraftVersion(String version) {
        LOGGER.info("minecraftVersion={}", version);
    }

    @Override
    public void setNeoForgeVersion(String version) {
        LOGGER.info("neoForgeVersion={}", version);
    }

    @Override
    public void crash(String message) {
        LOGGER.error("crash: {}", message);
    }

    @Override
    public void displayFatalErrorAndExit(List<ModLoadingIssue> issues, @Nullable Path modsFolder,
            @Nullable Path logFile, @Nullable Path crashReportFile) {
        LOGGER.error("fatal loading error, {} issue(s)", issues.size());
        Runtime.getRuntime().halt(1);
    }

    @Override
    public void stopEventPolling() {}

    @Override
    public WindowState handOverToMinecraft(Supplier<Object> renderBackend) {
        boolean adopted = "1".equals(System.getProperty(ADOPTED_PROPERTY));
        System.clearProperty(ADOPTED_PROPERTY);
        System.clearProperty(HANDLE_PROPERTY);
        handedOver = true;
        if (!adopted) {
            // P269: the game is not on our Direct3D 12 backend, so vanilla just created its own window. Ours must
            // not stay on screen as a stray second window.
            LOGGER.info("graphics backend is not D3D12 - dropping the early window, vanilla creates its own");
            close();
            if (window != 0L) {
                SDLVideo.SDL_HideWindow(window);
            }
            return new WindowState(0, 0, INITIAL_WIDTH, INITIAL_HEIGHT, false, false, false, false);
        }
        LOGGER.info("handing the early window over to Minecraft (same window, no recreate)");
        // Keep the last frame on screen: destroying the renderer here would leave the window black until
        // the game presents its first frame, which reads as a flash. Released in close().
        return new WindowState(0, 0, INITIAL_WIDTH, INITIAL_HEIGHT, false, false, false, false);
    }

    @Override
    public void periodicTick() {
        if (handedOver) {
            return;
        }
        // NOTE: do not drain SDL events here - SDL_PollEvent(NULL) keeps returning true while events are
        // pending and FML calls this method on the loading thread, so draining can spin forever (P268 hang).
        progressTicks++;
        if (progressTicks % 4 == 0) {
            redraw();
        }
    }

    @Override
    public void updateProgress(String label) {
        // The caption is fixed on purpose: it names the backend that is being brought up.
    }

    @Override
    public void completeProgress() {
        progressTicks = 50;
        redraw();
    }

    @Override
    public void close() {
        handedOver = true;
        if (renderer != 0L) {
            SDLRender.SDL_DestroyRenderer(renderer);
            renderer = 0L;
        }
    }
}
