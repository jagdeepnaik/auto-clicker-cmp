import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Draws the app icon (a pointer with click ripples, in Palette.Default's blue) and writes every
 * platform's copy. Run from the repository root on a Mac, since the .icns uses iconutil:
 *
 *   java composeApp/app-icons/IconGen.java
 */
public class IconGen {
    static final Path ICONS = Path.of("composeApp/app-icons");
    static final Path RES = Path.of("composeApp/src/androidMain/res");

    public static void main(String[] args) throws Exception {
        write(draw(1024, false), Path.of("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png"));
        write(draw(512, true), ICONS.resolve("autoclicker-icon-linux-512.png"));
        write(draw(512, true), Path.of("composeApp/src/jvmMain/resources/autoclicker-icon.png"));
        write(draw(256, true), Path.of("composeApp/src/commonMain/composeResources/drawable/autoclicker_logo.png"));
        ico(Path.of("composeApp/src/jvmMain/resources/autoclicker-icon.ico"));
        icns();
        String[][] densities = {{"mdpi", "48"}, {"hdpi", "72"}, {"xhdpi", "96"}, {"xxhdpi", "144"}, {"xxxhdpi", "192"}};
        for (String[] d : densities) {
            write(draw(Integer.parseInt(d[1]), true), RES.resolve("mipmap-" + d[0] + "/ic_launcher.png"));
        }
        System.out.println("Icons regenerated.");
    }

    /** Rounded keeps the transparent margin desktop icons expect; iOS applies its own mask. */
    static BufferedImage draw(int size, boolean rounded) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(size / 1024.0, size / 1024.0);

        g.setPaint(new GradientPaint(0, 0, new Color(0x2B7BF0), 0, 1024, new Color(0x0B57D0)));
        if (rounded) {
            g.fill(new RoundRectangle2D.Double(90, 90, 844, 844, 190, 190));
        } else {
            g.fillRect(0, 0, 1024, 1024);
        }

        // Click ripples around the pointer's tip.
        double tipX = 400, tipY = 370;
        g.setColor(new Color(255, 255, 255, 235));
        g.setStroke(new BasicStroke(44, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(tipX - 110, tipY - 110, 220, 220, 15, 240, Arc2D.OPEN));
        g.setColor(new Color(255, 255, 255, 130));
        g.draw(new Arc2D.Double(tipX - 205, tipY - 205, 410, 410, 25, 220, Arc2D.OPEN));

        // The pointer.
        Path2D pointer = new Path2D.Double();
        pointer.moveTo(0, 0);
        pointer.lineTo(0, 400);
        pointer.lineTo(98, 310);
        pointer.lineTo(165, 460);
        pointer.lineTo(245, 425);
        pointer.lineTo(178, 278);
        pointer.lineTo(300, 268);
        pointer.closePath();
        Path2D placed = (Path2D) pointer.createTransformedShape(AffineTransform.getTranslateInstance(tipX, tipY));
        g.setStroke(new BasicStroke(26, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0x0A3D91));
        g.draw(placed);
        g.setColor(Color.WHITE);
        g.fill(placed);
        g.dispose();
        return image;
    }

    static void write(BufferedImage image, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        ImageIO.write(image, "png", path.toFile());
    }

    static void icns() throws Exception {
        Path set = Files.createTempDirectory("autoclicker").resolve("AutoClicker.iconset");
        Files.createDirectories(set);
        for (int size : new int[] {16, 32, 128, 256, 512}) {
            write(draw(size, true), set.resolve("icon_" + size + "x" + size + ".png"));
            write(draw(size * 2, true), set.resolve("icon_" + size + "x" + size + "@2x.png"));
        }
        Process process = new ProcessBuilder("iconutil", "-c", "icns", set.toString(), "-o", ICONS.resolve("AutoClicker.icns").toString())
            .inheritIO().start();
        if (process.waitFor() != 0) throw new IllegalStateException("iconutil failed");
    }

    /** An ICO whose entries are PNGs, which every supported Windows version reads. */
    static void ico(Path path) throws IOException {
        int[] sizes = {16, 24, 32, 48, 64, 128, 256};
        List<byte[]> images = new ArrayList<>();
        for (int size : sizes) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(draw(size, true), "png", png);
            images.add(png.toByteArray());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);
        data.writeShort(0);
        data.writeShort(Short.reverseBytes((short) 1));
        data.writeShort(Short.reverseBytes((short) sizes.length));
        int offset = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            data.writeByte(sizes[i] == 256 ? 0 : sizes[i]);
            data.writeByte(sizes[i] == 256 ? 0 : sizes[i]);
            data.writeByte(0);
            data.writeByte(0);
            data.writeShort(Short.reverseBytes((short) 1));
            data.writeShort(Short.reverseBytes((short) 32));
            data.writeInt(Integer.reverseBytes(images.get(i).length));
            data.writeInt(Integer.reverseBytes(offset));
            offset += images.get(i).length;
        }
        for (byte[] image : images) data.write(image);
        Files.createDirectories(path.getParent());
        Files.write(path, out.toByteArray());
    }
}
