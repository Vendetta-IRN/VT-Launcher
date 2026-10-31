package vt.launcher.core;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/** Just enough NBT reading to get the world name out of level.dat. */
final class Nbt {
    private Nbt() {}

    /** Data.LevelName from a world's level.dat, or null. */
    static String levelName(Path levelDat) {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(levelDat)))) {
            if (in.readByte() != 10) return null;
            in.readUTF();
            while (true) {
                byte t = in.readByte();
                if (t == 0) return null;
                String name = in.readUTF();
                if (t == 10 && name.equals("Data")) return fromData(in);
                skip(in, t);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String fromData(DataInputStream in) throws IOException {
        while (true) {
            byte t = in.readByte();
            if (t == 0) return null;
            String name = in.readUTF();
            if (t == 8 && name.equals("LevelName")) return in.readUTF();
            skip(in, t);
        }
    }

    private static void skip(DataInputStream in, byte t) throws IOException {
        switch (t) {
            case 1 -> in.skipNBytes(1);
            case 2 -> in.skipNBytes(2);
            case 3, 5 -> in.skipNBytes(4);
            case 4, 6 -> in.skipNBytes(8);
            case 7 -> in.skipNBytes(in.readInt());
            case 8 -> in.readUTF();
            case 9 -> {
                byte lt = in.readByte();
                int len = in.readInt();
                for (int i = 0; i < len; i++) skip(in, lt);
            }
            case 10 -> {
                while (true) {
                    byte t2 = in.readByte();
                    if (t2 == 0) break;
                    in.readUTF();
                    skip(in, t2);
                }
            }
            case 11 -> in.skipNBytes(4L * in.readInt());
            case 12 -> in.skipNBytes(8L * in.readInt());
            default -> throw new IOException("Unknown NBT tag " + t);
        }
    }
}
