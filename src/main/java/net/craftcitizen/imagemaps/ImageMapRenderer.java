package net.craftcitizen.imagemaps;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;

public class ImageMapRenderer extends MapRenderer {
    private ImageMaps plugin;

    // written by commands (any thread), read by the region thread rendering the map
    private volatile BufferedImage image = null;
    private volatile boolean first = true;

    private final int x;
    private final int y;
    private final double scale;

    public ImageMapRenderer(ImageMaps plugin, BufferedImage image, int x, int y, double scale) {
        this.plugin = plugin;
        this.x = x;
        this.y = y;
        this.scale = scale;
        recalculateInput(image);
    }

    public void recalculateInput(BufferedImage input) {
        if (x * ImageMaps.MAP_WIDTH > Math.round(input.getWidth() * scale)
            || y * ImageMaps.MAP_HEIGHT > Math.round(input.getHeight() * scale))
            return;

        int x1 = (int) Math.floor(x * ImageMaps.MAP_WIDTH / scale);
        int y1 = (int) Math.floor(y * ImageMaps.MAP_HEIGHT / scale);

        int x2 = (int) Math.ceil(Math.min(input.getWidth(), ((x + 1) * ImageMaps.MAP_WIDTH / scale)));
        int y2 = (int) Math.ceil(Math.min(input.getHeight(), ((y + 1) * ImageMaps.MAP_HEIGHT / scale)));

        if (x2 - x1 <= 0 || y2 - y1 <= 0)
            return;

        BufferedImage part = input.getSubimage(x1, y1, x2 - x1, y2 - y1);

        if (scale != 1D) {
            BufferedImage resized = new BufferedImage(ImageMaps.MAP_WIDTH, ImageMaps.MAP_HEIGHT,
                                                      input.getType() == 0 ? part.getType() : input.getType());
            AffineTransform at = new AffineTransform();
            at.scale(scale, scale);
            AffineTransformOp scaleOp = new AffineTransformOp(at, AffineTransformOp.TYPE_BILINEAR);
            part = scaleOp.filter(part, resized);
        }

        // publish only the finished image, so a render on another thread never sees the unscaled cut-out
        this.image = part;
        first = true;
    }

    @Override
    public void render(MapView view, MapCanvas canvas, Player player) {
        if (image != null && first) {
            // spread out pseudo randomly in a very naive way
            long delay = Math.max(1L, System.nanoTime() % 60);
            first = false;
            // render() runs on the thread owning the viewer's region; draw there too. If the viewer leaves
            // before the task runs, let the next render schedule the draw again.
            if (player.getScheduler().runDelayed(plugin, task -> canvas.drawImage(0, 0, image), () -> first = true,
                                                 delay) == null)
                first = true;
        }
    }

}
