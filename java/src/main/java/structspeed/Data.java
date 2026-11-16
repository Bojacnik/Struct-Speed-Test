package structspeed;

import java.util.UUID;

/**
 * 16 bytes of data as two longs. Java has no user-defined value types yet (see java-preview/valhalla),
 * so every instance is a separate heap object and an array of Data holds references to them.
 */
public record Data(long lo, long hi) implements Comparable<Data> {

    public static Data fromUuid(UUID uuid) {
        return new Data(uuid.getLeastSignificantBits(), uuid.getMostSignificantBits());
    }

    /** Branch-free content comparison: equal only if both XORs are zero. */
    @Override
    public boolean equals(Object o) {
        return o instanceof Data other && ((lo ^ other.lo) | (hi ^ other.hi)) == 0;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(lo * 31 + hi);
    }

    /** Unsigned ordering by the high half, then the low half (same order as the C# version). */
    @Override
    public int compareTo(Data other) {
        return hi != other.hi ? Long.compareUnsigned(hi, other.hi) : Long.compareUnsigned(lo, other.lo);
    }
}
