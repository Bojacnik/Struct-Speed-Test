using System.Buffers.Binary;
using System.Numerics;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Runtime.Intrinsics;

namespace StructSpeedTest;

/// <summary>
/// 16 bytes of data stored inline as two 64-bit fields.
/// No heap allocation per instance: an array of Data is one contiguous block of memory.
/// </summary>
[StructLayout(LayoutKind.Sequential)]
public readonly struct Data : IEquatable<Data>, IComparable<Data>
{
    public const int Size = 16;

    private readonly ulong _lo;
    private readonly ulong _hi;

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public Data(ulong lo, ulong hi)
    {
        _lo = lo;
        _hi = hi;
    }

    public ulong Lo => _lo;
    public ulong Hi => _hi;

    public Data(ReadOnlySpan<byte> bytes)
    {
        if (bytes.Length != Size)
            throw new ArgumentException($"Expected exactly {Size} bytes.", nameof(bytes));

        _lo = BinaryPrimitives.ReadUInt64LittleEndian(bytes);
        _hi = BinaryPrimitives.ReadUInt64LittleEndian(bytes[8..]);
    }

    /// <summary>Reinterprets the 16 bytes of a Guid directly, no copy through a byte[].</summary>
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public Data(Guid guid)
    {
        this = Unsafe.As<Guid, Data>(ref guid);
    }

    public void WriteTo(Span<byte> destination)
    {
        BinaryPrimitives.WriteUInt64LittleEndian(destination, _lo);
        BinaryPrimitives.WriteUInt64LittleEndian(destination[8..], _hi);
    }

    // Content comparison, branch-free: equal only if both XORs are zero.
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public bool Equals(Data other) => ((_lo ^ other._lo) | (_hi ^ other._hi)) == 0;

    /// <summary>
    /// Counts how many items equal <paramref name="value"/> using SIMD:
    /// an array of structs is one flat block of memory, so 2 (AVX2) or 4 (AVX-512) items are compared per instruction.
    /// </summary>
    public static int CountMatches(ReadOnlySpan<Data> items, Data value)
    {
        var matches = 0;
        var i = 0;

        if (Vector512.IsHardwareAccelerated && items.Length >= 4)
        {
            var target = Vector256.Create(value._lo, value._hi, value._lo, value._hi);
            var target512 = Vector512.Create(target, target);
            var vectors = MemoryMarshal.Cast<Data, Vector512<ulong>>(items);
            foreach (var vector in vectors)
            {
                // 8 lane bits; an item matches when both of its lanes (bit pair) are set.
                var mask = Vector512.Equals(vector, target512).ExtractMostSignificantBits();
                matches += BitOperations.PopCount(mask & (mask >> 1) & 0x55UL);
            }
            i = vectors.Length * 4;
        }
        else if (Vector256.IsHardwareAccelerated && items.Length >= 2)
        {
            var target = Vector256.Create(value._lo, value._hi, value._lo, value._hi);
            var vectors = MemoryMarshal.Cast<Data, Vector256<ulong>>(items);
            foreach (var vector in vectors)
            {
                var mask = Vector256.Equals(vector, target).ExtractMostSignificantBits();
                matches += BitOperations.PopCount(mask & (mask >> 1) & 0x5u);
            }
            i = vectors.Length * 2;
        }

        for (; i < items.Length; i++)
            matches += items[i].Equals(value) ? 1 : 0;

        return matches;
    }

    public override bool Equals(object? obj) => obj is Data other && Equals(other);

    // Same cheap hash as the Java version (Long.hashCode(lo * 31 + hi)), so HashSet results compare like for like.
    public override int GetHashCode()
    {
        var x = _lo * 31 + _hi;
        return (int)(x ^ (x >> 32));
    }

    /// <summary>Unsigned ordering by the high half, then the low half (same order as the Java version).</summary>
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public int CompareTo(Data other) => _hi != other._hi ? _hi.CompareTo(other._hi) : _lo.CompareTo(other._lo);

    public override string ToString() => $"{_hi:x16}{_lo:x16}";

    public static bool operator ==(Data left, Data right) => left.Equals(right);
    public static bool operator !=(Data left, Data right) => !left.Equals(right);
}
