using System;
using System.Diagnostics;

namespace TestingSpeed;

internal class Program
{
    public static void Main(string[] args)
    {
        var stopwatch = new Stopwatch();
        var dataArray = new Data[50000];

        // Creation of Data structs
        stopwatch.Start();
        for (var i = 0; i < dataArray.Length; i++)
        {
            dataArray[i] = new Data(new byte[16]);
        }

        stopwatch.Stop();
        Console.WriteLine("Elapsed time during creation of data structs: " + stopwatch.Elapsed.TotalMilliseconds +
                          "ms");
        stopwatch.Reset();

        // Creation of GUIDs
        stopwatch.Start();
        for (var i = 0; i < dataArray.Length; i++)
        {
            dataArray[i] = new Data(new Guid().ToByteArray());
        }

        stopwatch.Stop();
        Console.WriteLine("Elapsed time during creation of GUIDs: " + stopwatch.Elapsed.TotalMilliseconds + "ms");
        stopwatch.Reset();

        // Comparison of the first element in dataArray against every element in the dataArray
        stopwatch.Start();
        foreach (var dataUnit in dataArray)
        {
            dataArray[0].Equals(dataUnit);
        }

        stopwatch.Stop();
        Console.WriteLine(
            "Elapsed time during the comparison of the first element against every other element in the dataArray: "
            + stopwatch.Elapsed.TotalMilliseconds + "ms");
        stopwatch.Reset();
    }
}

internal readonly struct Data : IEquatable<Data>
{
    private readonly byte[] _buffer;

    public Data(byte[] bytes)
    {
        _buffer = bytes;
    }

    public override bool Equals(object obj)
    {
        return obj is Data data && Equals(data);
    }

    public bool Equals(Data other)
    {
        return _buffer.Equals(other._buffer);
    }

    public override int GetHashCode()
    {
        return HashCode.Combine(_buffer.ToString());
    }

    //Operators
    public static bool operator ==(Data d1, Data d2) => d1.Equals(d2);
    public static bool operator !=(Data d1, Data d2) => !(d1.Equals(d2));
}