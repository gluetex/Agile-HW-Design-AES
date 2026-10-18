import chisel3._

//Building blocks of the key schedule (FIPS-197 5.2)
//A word is 4 bytes, byte 0 is the most significant one (like in the NIST hex strings).
object KeyExpansion {
  type Word = Seq[UInt]

  //Rcon[j] = x^(j-1) in GF(2^8), only the first byte of the word is non-zero (FIPS-197 table 5)
  //AES-128 needs Rcon[1..10], AES-192 needs Rcon[1..8] and AES-256 needs Rcon[1..7]
  val rcon: Seq[Int] = Seq(0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36)

  //[a0, a1, a2, a3] -> [a1, a2, a3, a0]
  def rotWord(w: Word): Word = w.tail :+ w.head

  //Put every byte through the S-box
  def subWord(w: Word): Word = w.map(SBox(_))
}

//Calculates all the round keys from the cipher key (FIPS-197 5.2), without any clock cycles.
//The output is what AESEncrypt wants: roundKeys(i) is made from the words w[4i .. 4i+3],
//roundKeys(i).rows(r)(c) = byte r of word w[4i + c].
//
//keySize 128: Nk = 4, Nr = 10    keySize 192: Nk = 6, Nr = 12    keySize 256: Nk = 8, Nr = 14
class KeyExpansion(val keySize: Int = 128) extends Module {
  require(Seq(128, 192, 256).contains(keySize), "AES key size must be 128, 192 or 256")
  val nk = keySize / 32 //words in the key
  val nr = AESEncrypt.rounds(keySize)

  val io = IO(new Bundle {
    val key = Input(UInt(keySize.W))
    val roundKeys = Output(Vec(nr + 1, new State))
  })

  import KeyExpansion._

  //The first Nk words are the key itself, byte 0 of the key is the MSB
  val w = scala.collection.mutable.ArrayBuffer.empty[Word]
  for (i <- 0 until nk) {
    w += Seq.tabulate(4)(b => io.key(keySize - 1 - 8 * (4 * i + b), keySize - 8 * (4 * i + b + 1)))
  }

  //Every other word is w[i-Nk] xor temp, where temp depends on i
  for (i <- nk until 4 * (nr + 1)) {
    val temp: Word =
      if (i % nk == 0) {
        //SubWord(RotWord(w[i-1])) xor Rcon[i/Nk]
        val t = subWord(rotWord(w(i - 1)))
        (t.head ^ rcon(i / nk - 1).U(8.W)) +: t.tail
      } else if (nk > 6 && i % nk == 4) {
        subWord(w(i - 1)) //only for AES-256
      } else {
        w(i - 1)
      }
    w += w(i - nk).zip(temp).map { case (a, b) => a ^ b }
  }

  for (k <- 0 to nr; r <- 0 to 3; c <- 0 to 3) {
    io.roundKeys(k).rows(r)(c) := w(4 * k + c)(r)
  }
}
