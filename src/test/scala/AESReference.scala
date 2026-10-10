import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import scala.io.Source

//Software models used to test the AES hardware
object AESReference {
  def hexToBytes(hex: String): Seq[Int] =
    hex.grouped(2).map(Integer.parseInt(_, 16)).toSeq

  def bytesToBigInt(bytes: Seq[Int]): BigInt =
    bytes.foldLeft(BigInt(0))((acc, b) => (acc << 8) | b)

  def bigIntToBytes(x: BigInt, n: Int): Seq[Int] =
    Seq.tabulate(n)(i => ((x >> (8 * (n - 1 - i))) & 0xff).toInt)

  private def xtime(b: Int): Int = if ((b & 0x80) != 0) ((b << 1) ^ 0x11b) else (b << 1)

  //Software KeyExpansion (FIPS-197 5.2), works for 16, 24 and 32 byte keys.
  //Returns the round keys as states: roundKeys(i)(r)(c) = byte r of word w[4i + c].
  //This is only here to feed the AESEncrypt core until the KeyExpansion module is done.
  def expandKey(key: Seq[Int]): Seq[Seq[Seq[Int]]] = {
    val nk = key.length / 4
    val nr = nk + 6
    val w = scala.collection.mutable.ArrayBuffer.tabulate(nk)(i => key.slice(4 * i, 4 * i + 4))
    var rcon = 0x01
    for (i <- nk until 4 * (nr + 1)) {
      var temp = w(i - 1)
      if (i % nk == 0) {
        temp = (temp.tail :+ temp.head).map(SBox.table) //SubWord(RotWord(temp))
        temp = (temp.head ^ rcon) +: temp.tail
        rcon = xtime(rcon)
      } else if (nk > 6 && i % nk == 4) {
        temp = temp.map(SBox.table)
      }
      w += w(i - nk).zip(temp).map { case (a, b) => a ^ b }
    }
    Seq.tabulate(nr + 1)(k => Seq.tabulate(4, 4)((r, c) => w(4 * k + c)(r)))
  }

  //Independent reference: the AES implementation in the JDK
  def encryptJdk(key: Seq[Int], block: Seq[Int]): Seq[Int] = {
    val cipher = Cipher.getInstance("AES/ECB/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.map(_.toByte).toArray, "AES"))
    cipher.doFinal(block.map(_.toByte).toArray).map(_ & 0xff).toSeq
  }
}

//Parser for the NIST AESAVS response files (.rsp) in src/test/resources/nist.
//Only the [ENCRYPT] part is read. PLAINTEXT and CIPHERTEXT can hold several
//blocks (the MMT files), they are split into 16 byte blocks.
object NistVectors {
  case class Vector(count: Int, key: Seq[Int], plaintext: Seq[BigInt], ciphertext: Seq[BigInt])

  private def blocks(hex: String): Seq[BigInt] = hex.grouped(32).map(BigInt(_, 16)).toSeq

  def load(file: String): Seq[Vector] = {
    val source = Source.fromResource(s"nist/$file")
    val lines = try source.getLines().map(_.trim).toList finally source.close()
    val encrypt = lines.dropWhile(_ != "[ENCRYPT]").drop(1).takeWhile(_ != "[DECRYPT]")

    val records = encrypt.foldLeft(List(Map.empty[String, String])) { (acc, line) =>
      if (line.isEmpty || line.startsWith("#")) {
        if (acc.head.isEmpty) acc else Map.empty[String, String] :: acc
      } else {
        val Array(name, value) = line.split("=").map(_.trim)
        (acc.head + (name -> value)) :: acc.tail
      }
    }.filter(_.nonEmpty).reverse

    val vectors = records.map { r =>
      Vector(r("COUNT").toInt, AESReference.hexToBytes(r("KEY")), blocks(r("PLAINTEXT")), blocks(r("CIPHERTEXT")))
    }
    require(vectors.nonEmpty, s"no test vectors found in $file")
    vectors
  }
}
