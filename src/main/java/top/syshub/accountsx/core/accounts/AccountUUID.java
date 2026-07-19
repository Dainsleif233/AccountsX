package top.syshub.accountsx.core.accounts;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

public final class AccountUUID {
    private AccountUUID() {
    }

    private static final byte[] NIBBLES;

    static {
        byte[] ns = new byte[256];
        Arrays.fill(ns, (byte) -1);

        ns['0'] = 0;
        ns['1'] = 1;
        ns['2'] = 2;
        ns['3'] = 3;
        ns['4'] = 4;
        ns['5'] = 5;
        ns['6'] = 6;
        ns['7'] = 7;
        ns['8'] = 8;
        ns['9'] = 9;
        ns['A'] = 10;
        ns['B'] = 11;
        ns['C'] = 12;
        ns['D'] = 13;
        ns['E'] = 14;
        ns['F'] = 15;
        ns['a'] = 10;
        ns['b'] = 11;
        ns['c'] = 12;
        ns['d'] = 13;
        ns['e'] = 14;
        ns['f'] = 15;
        NIBBLES = ns;
    }

    private static long parse4Nibbles(String name, int pos) {
        char ch1 = name.charAt(pos);
        char ch2 = name.charAt(pos + 1);
        char ch3 = name.charAt(pos + 2);
        char ch4 = name.charAt(pos + 3);

        byte[] ns = NIBBLES;
        return (ch1 | ch2 | ch3 | ch4) > 0xff ?
                -1 : ns[ch1] << 12 | ns[ch2] << 8 | ns[ch3] << 4 | ns[ch4];
    }

    public static UUID ofPlayerName(String playerName) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
    }

    public static String toMinecraftStyleString(UUID value) {
        return value.toString().replace("-", "");
    }

    public static UUID parse(String value) throws IllegalArgumentException {
        switch (value.length()) {
            case 36 -> {
                char ch1 = value.charAt(8);
                char ch2 = value.charAt(13);
                char ch3 = value.charAt(18);
                char ch4 = value.charAt(23);
                if (ch1 == '-' && ch2 == '-' && ch3 == '-' && ch4 == '-') {
                    return calculateUUID(
                            value,
                            parse4Nibbles(value, 0),
                            parse4Nibbles(value, 4),
                            parse4Nibbles(value, 9),
                            parse4Nibbles(value, 14),
                            parse4Nibbles(value, 19),
                            parse4Nibbles(value, 24),
                            parse4Nibbles(value, 28),
                            parse4Nibbles(value, 32)
                    );
                } else {
                    throw new IllegalArgumentException("Illegal UUID: " + value);
                }
            }
            case 32 -> {
                return calculateUUID(
                        value,
                        parse4Nibbles(value, 0),
                        parse4Nibbles(value, 4),
                        parse4Nibbles(value, 8),
                        parse4Nibbles(value, 12),
                        parse4Nibbles(value, 16),
                        parse4Nibbles(value, 20),
                        parse4Nibbles(value, 24),
                        parse4Nibbles(value, 28)
                );
            }
            default -> throw new IllegalArgumentException("Illegal UUID: " + value);
        }
    }

    private static UUID calculateUUID(String value, long msb1, long msb2, long msb3, long msb4, long lsb1, long lsb2, long lsb3, long lsb4) throws IllegalArgumentException {
        if ((msb1 | msb2 | msb3 | msb4 | lsb1 | lsb2 | lsb3 | lsb4) >= 0) {
            return new UUID(
                    msb1 << 48 | msb2 << 32 | msb3 << 16 | msb4,
                    lsb1 << 48 | lsb2 << 32 | lsb3 << 16 | lsb4
            );
        } else {
            throw new IllegalArgumentException("Illegal UUID: " + value);
        }
    }

    public static class UUIDTypeAdapter extends TypeAdapter<UUID> {
        @Override
        public void write(final JsonWriter out, final UUID value) throws IOException {
            out.value(toMinecraftStyleString(value));
        }

        @Override
        public UUID read(final JsonReader in) throws IOException {
            return parse(in.nextString());
        }
    }
}
