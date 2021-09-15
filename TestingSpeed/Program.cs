using System;
using System.Diagnostics;

namespace TestingSpeed
{
    class Program
    {
        static Stopwatch stopky = new Stopwatch();
        static Data[] dat = new Data[50000];
        static void Main(string[] args)
        {
            //Vytvoření s jednoduchou inicializací
            stopky.Start();
            for (int i = 0; i < dat.Length; i++)
            {
                dat[i] = new Data(new byte[16]);
            }
            stopky.Stop();
            Console.WriteLine("Při jednoduchém tvoření uběhlo: " + stopky.Elapsed.TotalMilliseconds);
            stopky.Reset();

            //Vytvoření pomocí Guidů (které téměř zaručeně vytvoří unikátní hodnotu)
            stopky.Start();
            for (int i = 0; i < dat.Length; i++)
            {
                dat[i] = new Data(new Guid().ToByteArray());
            }
            stopky.Stop();
            Console.WriteLine("Při GUIDovém tvoření uběhlo: " + stopky.Elapsed.TotalMilliseconds);
            stopky.Reset();

            //Porovnávání prvního elementu se všemi ostatními
            stopky.Start();
            for (int i = 0; i < dat.Length; i++)
            {
                dat[0].Equals(dat[i]);
            }
            stopky.Stop();
            Console.WriteLine("Při porovnávání prvního s každým uběhlo: " + stopky.Elapsed.TotalMilliseconds);
            stopky.Reset();
        }
    }

    struct Data : IEquatable<Data>
    {
        byte[] buffer;

        //Equals
        public override bool Equals(object obj)
        {
            if (obj is not Data) return false;
            return Equals((Data) obj);
        }
        public bool Equals(Data other)
        {
            return buffer.Equals(other.buffer);
        }
        public override int GetHashCode()
        {
            return HashCode.Combine(buffer.ToString());
        }

        //Operators
        public static bool operator ==(Data d1, Data d2) => d1.Equals(d2);
        public static bool operator !=(Data d1, Data d2) => !(d1.Equals(d2));

        public Data (byte[] bytes)
        {
            buffer = bytes;
        }
    }
}
